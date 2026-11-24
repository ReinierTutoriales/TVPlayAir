#include "shared_ring_buffer.hpp"
#include "video_consumer_ring.hpp"

#include <array>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <linux/memfd.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>

using tvplayair::RingResult;
using tvplayair::SharedRingBuffer;

static int makeSharedFd(size_t bytes) {
    const int fd = ::memfd_create("tvplayair-spsc", MFD_CLOEXEC);
    assert(fd >= 0);
    assert(::ftruncate(fd, static_cast<off_t>(bytes)) == 0);
    return fd;
}

static void testOverflowAndGeneration() {
    constexpr uint32_t capacity = 4, payload = 64;
    const size_t bytes = SharedRingBuffer::requiredBytes(capacity, payload);
    SharedRingBuffer ring;
    assert(ring.mapFd(makeSharedFd(bytes), bytes, true, capacity, payload));

    const char value[] = "packet";
    for (uint32_t generation = 1; generation <= capacity; ++generation)
        assert(ring.writePacket(value, sizeof(value), generation) == RingResult::Ok);
    assert(ring.writePacket(value, sizeof(value), 5) == RingResult::Full);
    assert(ring.droppedPackets() == 1);

    std::array<char, payload> out{};
    uint32_t outSize = 0;
    ring.discardPending();
    assert(ring.readPacket(out.data(), out.size(), &outSize, capacity) == RingResult::Empty);
    assert(ring.droppedPackets() == 1);
    for (uint32_t generation = 1; generation <= capacity; ++generation) {
        assert(ring.writePacket(value, sizeof(value), generation) == RingResult::Ok);
        assert(ring.readPacket(out.data(), out.size(), &outSize, generation) == RingResult::Ok);
        assert(outSize == sizeof(value) && std::memcmp(out.data(), value, sizeof(value)) == 0);
    }
    assert(ring.writePacket(value, payload + 1, 9) == RingResult::Oversized);
    assert(ring.writePacket(value, sizeof(value), 10) == RingResult::Ok);
    assert(ring.readPacket(out.data(), out.size(), &outSize, 11) == RingResult::StaleGeneration);
    assert(ring.readPacket(out.data(), out.size(), &outSize, 11) == RingResult::Empty);
}

static void testAudioStartupDiscardsOlderPacketsAndRetainsNewAudio() {
    constexpr uint32_t capacity = 4, payload = 64;
    const size_t bytes = SharedRingBuffer::requiredBytes(capacity, payload);
    SharedRingBuffer ring;
    assert(ring.mapFd(makeSharedFd(bytes), bytes, true, capacity, payload));

    const uint32_t old = 10, current = 20, later = 30;
    assert(ring.writePacket(&old, sizeof(old), UINT32_MAX) == RingResult::Ok);
    assert(ring.writePacket(&old, sizeof(old), UINT32_MAX) == RingResult::Ok);
    assert(ring.writePacket(&current, sizeof(current), 0) == RingResult::Ok);
    assert(ring.writePacket(&later, sizeof(later), 1) == RingResult::Ok);
    assert(ring.writePacket(&later, sizeof(later), 1) == RingResult::Full);

    assert(ring.discardOlderGenerations(0) == 2);
    assert(ring.writePacket(&later, sizeof(later), 1) == RingResult::Ok);
    uint32_t out = 0, outSize = 0;
    assert(ring.readPacket(&out, sizeof(out), &outSize, 0) == RingResult::Ok && out == current);
    assert(ring.discardOlderGenerations(0) == 0);
    assert(ring.readPacket(&out, sizeof(out), &outSize, 1) == RingResult::Ok && out == later);
}

static void testWrapAroundAndNoLivelock() {
    constexpr uint32_t capacity = 8, payload = 32;
    const size_t bytes = SharedRingBuffer::requiredBytes(capacity, payload);
    SharedRingBuffer ring;
    assert(ring.mapFd(makeSharedFd(bytes), bytes, true, capacity, payload));

    uint32_t outSize = 0, out = 0;
    for (uint32_t i = 0; i < 200000; ++i) {
        const uint32_t generation = i + 1; // generation changes on every push
        const uint32_t value = i ^ 0xA5A5A5A5u;
        assert(ring.writePacket(&value, sizeof(value), generation) == RingResult::Ok);
        assert(ring.readPacket(&out, sizeof(out), &outSize, generation) == RingResult::Ok);
        assert(outSize == sizeof(out) && out == value);
    }
    const uint8_t oversized[payload + 1]{};
    assert(ring.writePacket(oversized, sizeof(oversized), 7) == RingResult::Oversized);
    assert(ring.readPacket(&out, sizeof(out), &outSize, 7) == RingResult::Empty);
}

static void testForkSharedMemorySpsc() {
    constexpr uint32_t capacity = 64, payload = sizeof(uint32_t);
    const size_t bytes = SharedRingBuffer::requiredBytes(capacity, payload);
    const int sharedFd = makeSharedFd(bytes);
    SharedRingBuffer initializer;
    assert(initializer.mapFd(::dup(sharedFd), bytes, true, capacity, payload));
    initializer.detach();

    const pid_t child = ::fork();
    assert(child >= 0);
    if (child == 0) {
        SharedRingBuffer producer;
        if (!producer.mapFd(::dup(sharedFd), bytes, false)) _exit(10);
        for (uint32_t i = 0; i < 100000; ++i) {
            while (producer.writePacket(&i, sizeof(i), i + 1) == RingResult::Full) {}
        }
        _exit(0);
    }

    SharedRingBuffer consumer;
    assert(consumer.mapFd(::dup(sharedFd), bytes, false));
    uint32_t expected = 0, value = 0, size = 0;
    while (expected < 100000) {
        const RingResult result = consumer.readPacket(&value, sizeof(value), &size, expected + 1);
        if (result == RingResult::Empty) continue;
        assert(result == RingResult::Ok);
        assert(size == sizeof(value) && value == expected);
        ++expected;
    }
    int status = 0;
    assert(::waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    consumer.detach();
    ::close(sharedFd);
}

static void testVideoConsumerRemapInvalidatesStaleWorker() {
    constexpr uint32_t capacity = 4, payload = 64, generation = 1;
    const size_t bytes = SharedRingBuffer::requiredBytes(capacity, payload);
    const int sharedFd = makeSharedFd(bytes);
    SharedRingBuffer producer;
    assert(producer.mapFd(::dup(sharedFd), bytes, true, capacity, payload));
    tvplayair::VideoConsumerRing consumer;
    assert(consumer.mapFd(::dup(sharedFd), bytes));
    const int64_t oldEpoch = consumer.epoch();
    std::atomic<bool> run{true};
    std::thread oldWorker([&] {
        uint32_t value = 0, size = 0;
        while (run.load()) {
            const auto result = consumer.readPacket(oldEpoch, &value, sizeof(value), &size, generation);
            assert(result == RingResult::Empty || result == RingResult::Invalid);
            consumer.discardPending(oldEpoch);
        }
    });

    for (int i = 0; i < 100; ++i) {
        consumer.detach();
        assert(consumer.mapFd(::dup(sharedFd), bytes));
    }
    const uint32_t packet = 0x1234;
    assert(producer.writePacket(&packet, sizeof(packet), generation) == RingResult::Ok);
    uint32_t value = 0, size = 0;
    assert(consumer.readPacket(oldEpoch, &value, sizeof(value), &size, generation) == RingResult::Invalid);
    consumer.discardPending(oldEpoch);
    assert(consumer.readPacket(consumer.epoch(), &value, sizeof(value), &size, generation) == RingResult::Ok);
    assert(size == sizeof(packet) && value == packet);

    run.store(false);
    oldWorker.join();
    ::close(sharedFd);
}

int main() {
    testOverflowAndGeneration();
    testVideoConsumerRemapInvalidatesStaleWorker();
    testAudioStartupDiscardsOlderPacketsAndRetainsNewAudio();
    testWrapAroundAndNoLivelock();
    testForkSharedMemorySpsc();
    std::puts("SPSC host tests passed: fork/MAP_SHARED, wrap-around, overflow, generation and bounded oversized-packet handling.");
}
