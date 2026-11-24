#pragma once

#include <mutex>

#include "shared_ring_buffer.hpp"

namespace tvplayair {

// Guard the main process mapping lifetime. Neither the producer nor Oboe takes this lock.
class VideoConsumerRing {
public:
    bool mapFd(int fd, size_t bytes) {
        const std::lock_guard<std::mutex> lock(mutex_);
        ++epoch_;
        return ring_.mapFd(fd, bytes, false);
    }

    void detach() {
        const std::lock_guard<std::mutex> lock(mutex_);
        ++epoch_;
        ring_.detach();
    }

    int64_t epoch() {
        const std::lock_guard<std::mutex> lock(mutex_);
        return epoch_;
    }

    RingResult readPacket(int64_t epoch, void* dst, uint32_t capacity, uint32_t* size, uint32_t generation) {
        const std::lock_guard<std::mutex> lock(mutex_);
        if (epoch != epoch_) return RingResult::Invalid;
        return ring_.readPacket(dst, capacity, size, generation);
    }

    void discardPending(int64_t epoch) {
        const std::lock_guard<std::mutex> lock(mutex_);
        if (epoch == epoch_) ring_.discardPending();
    }

private:
    std::mutex mutex_;
    SharedRingBuffer ring_;
    int64_t epoch_ = 0;
};
} // namespace tvplayair
