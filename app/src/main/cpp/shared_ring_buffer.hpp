#pragma once

#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <new>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <android/sharedmem.h>
#endif

namespace tvplayair {

static_assert(std::atomic<uint32_t>::is_always_lock_free,
              "SPSC requiere atomics lock-free");

constexpr uint32_t kRingMagic = 0x5456504C;
constexpr uint32_t kRingVersion = 1;
constexpr size_t kCacheLine = 64;

enum class RingResult : uint32_t {
    Ok, Empty, Full, Oversized, BufferTooSmall, StaleGeneration, Invalid
};

struct alignas(kCacheLine) RingHeader {
    uint32_t magic;
    uint32_t version;
    uint32_t capacity;
    uint32_t slotSize;
    uint32_t payloadCapacity;
    alignas(kCacheLine) std::atomic<uint32_t> writePos;
    alignas(kCacheLine) std::atomic<uint32_t> readPos;
    std::atomic<uint32_t> droppedPackets;
};

struct PacketHeader {
    uint32_t payloadSize;
    uint32_t generationId;
};

// POSIX-only mapping and SPSC data plane. Android FD creation is kept outside.
class SharedRingBuffer final {
public:
    SharedRingBuffer() = default;
    SharedRingBuffer(const SharedRingBuffer&) = delete;
    SharedRingBuffer& operator=(const SharedRingBuffer&) = delete;
    ~SharedRingBuffer() { detach(); }

    static size_t requiredBytes(uint32_t capacity, uint32_t payloadCapacity) {
        if (capacity < 2 || (capacity & (capacity - 1)) != 0 ||
            payloadCapacity == 0) return 0;
        const size_t stride = alignUp(sizeof(PacketHeader) + payloadCapacity);
        return alignUp(sizeof(RingHeader)) + stride * capacity;
    }

    // Takes ownership of fd and closes it immediately after mmap, on success
    // or failure. Caller must pass a descriptor whose ownership was detached.
    bool mapFd(int fd, size_t bytes, bool initialize, uint32_t capacity = 0,
               uint32_t payloadCapacity = 0) {
        if (fd < 0 || bytes < sizeof(RingHeader)) {
            if (fd >= 0) ::close(fd);
            return false;
        }
        if (mapping_) {
            detach();
        }
#ifdef __ANDROID__
        const size_t fdSize = ASharedMemory_getSize(fd);
        if (fdSize < bytes) {
            ::close(fd);
            return false;
        }
#else
        struct stat st {};
        if (::fstat(fd, &st) != 0 || st.st_size < static_cast<off_t>(bytes)) {
            ::close(fd);
            return false;
        }
#endif
        void* mapped = ::mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        const int mapError = errno;
        ::close(fd);
        if (mapped == MAP_FAILED) {
            errno = mapError;
            return false;
        }

        mapping_ = mapped;
        mappingBytes_ = bytes;
        header_ = static_cast<RingHeader*>(mapped);
        if (initialize) {
            const size_t required = requiredBytes(capacity, payloadCapacity);
            if (required == 0 || required > bytes) {
                detach();
                return false;
            }
            std::memset(mapped, 0, required);
            header_ = new (mapped) RingHeader;
            header_->magic = kRingMagic;
            header_->version = kRingVersion;
            header_->capacity = capacity;
            header_->slotSize = static_cast<uint32_t>(
                alignUp(sizeof(PacketHeader) + payloadCapacity));
            header_->payloadCapacity = payloadCapacity;
            header_->writePos.store(0, std::memory_order_relaxed);
            header_->readPos.store(0, std::memory_order_relaxed);
            header_->droppedPackets.store(0, std::memory_order_relaxed);
        }
        if (!valid()) {
            detach();
            return false;
        }
        return true;
    }

    void detach() {
        if (mapping_) {
            ::munmap(mapping_, mappingBytes_);
            mapping_ = nullptr;
            mappingBytes_ = 0;
            header_ = nullptr;
        }
    }

    RingResult writePacket(const void* data, uint32_t size, uint32_t generationId) {
        if (!valid() || (!data && size != 0)) return RingResult::Invalid;
        if (size > header_->payloadCapacity) {
            header_->droppedPackets.fetch_add(1, std::memory_order_relaxed);
            return RingResult::Oversized;
        }
        const uint32_t write = header_->writePos.load(std::memory_order_relaxed);
        const uint32_t read = header_->readPos.load(std::memory_order_acquire);
        if (static_cast<uint32_t>(write - read) >= header_->capacity) {
            header_->droppedPackets.fetch_add(1, std::memory_order_relaxed);
            return RingResult::Full;
        }
        PacketHeader* packet = packetAt(write);
        packet->payloadSize = size;
        packet->generationId = generationId;
        if (size) std::memcpy(payloadAt(packet), data, size);
        header_->writePos.store(write + 1, std::memory_order_release);
        return RingResult::Ok;
    }

    RingResult readPacket(void* out, uint32_t outCapacity, uint32_t* outSize,
                          uint32_t expectedGenerationId) {
        if (!valid() || !outSize || (!out && outCapacity != 0)) return RingResult::Invalid;
        const uint32_t read = header_->readPos.load(std::memory_order_relaxed);
        const uint32_t write = header_->writePos.load(std::memory_order_acquire);
        if (read == write) return RingResult::Empty;

        PacketHeader* packet = packetAt(read);
        const uint32_t size = packet->payloadSize;
        if (packet->generationId != expectedGenerationId) {
            header_->readPos.store(read + 1, std::memory_order_release);
            return RingResult::StaleGeneration;
        }
        if (size > header_->payloadCapacity) {
            header_->readPos.store(read + 1, std::memory_order_release);
            return RingResult::Invalid;
        }
        if (size > outCapacity) {
            header_->readPos.store(read + 1, std::memory_order_release);
            return RingResult::BufferTooSmall;
        }
        if (size) std::memcpy(out, payloadAt(packet), size);
        *outSize = size;
        header_->readPos.store(read + 1, std::memory_order_release);
        return RingResult::Ok;
    }

    // Consumer only: discard the backlog present at this instant without copying it.
    void discardPending() {
        if (!valid()) return;
        const uint32_t write = header_->writePos.load(std::memory_order_acquire);
        header_->readPos.store(write, std::memory_order_release);
    }

    uint32_t droppedPackets() const {
        return valid() ? header_->droppedPackets.load(std::memory_order_relaxed) : 0;
    }

private:
    static size_t alignUp(size_t value) {
        return (value + kCacheLine - 1) & ~(kCacheLine - 1);
    }
    bool valid() const {
        if (!header_ || !mapping_ || header_->magic != kRingMagic ||
            header_->version != kRingVersion || header_->capacity < 2 ||
            (header_->capacity & (header_->capacity - 1)) != 0 ||
            header_->slotSize <= sizeof(PacketHeader) ||
            header_->payloadCapacity == 0 ||
            header_->payloadCapacity > header_->slotSize - sizeof(PacketHeader)) return false;
        const size_t required = alignUp(sizeof(RingHeader)) +
            static_cast<size_t>(header_->slotSize) * header_->capacity;
        return required <= mappingBytes_;
    }
    PacketHeader* packetAt(uint32_t position) const {
        auto* base = static_cast<std::byte*>(mapping_) + alignUp(sizeof(RingHeader));
        return reinterpret_cast<PacketHeader*>(base +
            static_cast<size_t>(position & (header_->capacity - 1)) * header_->slotSize);
    }
    static void* payloadAt(PacketHeader* packet) {
        return reinterpret_cast<std::byte*>(packet) + sizeof(PacketHeader);
    }

    void* mapping_ = nullptr;
    size_t mappingBytes_ = 0;
    RingHeader* header_ = nullptr;
};

} // namespace tvplayair
