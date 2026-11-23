#include "audio_pcm_pipeline.hpp"

#include <array>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <linux/memfd.h>
#include <unistd.h>
#include <vector>

using tvplayair::AudioPcmPipeline;
using tvplayair::RingResult;
using tvplayair::SharedRingBuffer;
using tvplayair::kAudioBytesPerFrame;
using tvplayair::kAudioChannels;
using tvplayair::kAudioFramesPerMs;
using tvplayair::kMaxAudioOffsetMs;
using tvplayair::kMinAudioOffsetMs;

static int makeAudioSharedFd(size_t bytes) {
    const int fd = ::memfd_create("tvplayair-audio-test", MFD_CLOEXEC);
    assert(fd >= 0);
    assert(::ftruncate(fd, static_cast<off_t>(bytes)) == 0);
    return fd;
}

static void initAudioRing(SharedRingBuffer& ring) {
    constexpr uint32_t capacity = 64;
    constexpr uint32_t payload = 4096;
    const size_t bytes = SharedRingBuffer::requiredBytes(capacity, payload);
    assert(ring.mapFd(makeAudioSharedFd(bytes), bytes, true, capacity, payload));
}

static void testPacketBoundariesAndStreaming() {
    SharedRingBuffer ring;
    initAudioRing(ring);

    AudioPcmPipeline pipeline;
    pipeline.setGenerationId(1);

    // Push 3 packets of 200 stereo frames each (600 frames total).
    int16_t sampleCounter = 1;
    for (int pkt = 0; pkt < 3; ++pkt) {
        std::array<int16_t, 200 * kAudioChannels> src{};
        for (auto& s : src) {
            s = sampleCounter++;
        }
        assert(ring.writePacket(src.data(),
                                static_cast<uint32_t>(src.size() * sizeof(int16_t)),
                                1) == RingResult::Ok);
    }

    // Drain in mismatched Oboe burst sizes: 96, 192, 240, 72 = 600 frames.
    const std::array<int32_t, 4> bursts = {96, 192, 240, 72};
    int16_t expected = 1;
    for (int32_t frames : bursts) {
        std::vector<int16_t> out(static_cast<size_t>(frames) * kAudioChannels, -1);
        pipeline.render(ring, out.data(), frames);
        for (int16_t s : out) {
            assert(s == expected++);
        }
    }
    assert(pipeline.underrunCount() == 0);
}

static void testUnderrunOutputsSilence() {
    SharedRingBuffer ring;
    initAudioRing(ring);

    AudioPcmPipeline pipeline;
    pipeline.setGenerationId(2);

    std::array<int16_t, 192 * kAudioChannels> out{};
    out.fill(0x1234);
    pipeline.render(ring, out.data(), 192);
    for (int16_t s : out) {
        assert(s == 0);
    }
    assert(pipeline.underrunCount() == 1);
    assert(pipeline.underrunFrames() == 192);
}

static void testPrebufferAbsorbsFragmentedArrivalAndRebuffersAfterUnderrun() {
    SharedRingBuffer ring;
    initAudioRing(ring);
    AudioPcmPipeline pipeline(384);
    pipeline.setGenerationId(4);
    std::array<int16_t, 192 * kAudioChannels> packet{};
    std::array<int16_t, 192 * kAudioChannels> out{};
    packet.fill(1234);
    const auto publish = [&](uint32_t generation) {
        assert(ring.writePacket(packet.data(), sizeof(packet), generation) == RingResult::Ok);
    };
    const auto render = [&] {
        pipeline.render(ring, out.data(), 192);
    };

    publish(3);
    publish(4);
    render();
    assert(out.front() == 0 && out.back() == 0);
    assert(pipeline.underrunCount() == 0);

    publish(4);
    render();
    assert(out.front() == 1234 && out.back() == 1234);
    render();
    assert(out.front() == 1234);
    render();
    assert(out.front() == 0);
    assert(pipeline.underrunFrames() == 192);

    publish(4);
    render();
    assert(out.front() == 0);
    assert(pipeline.underrunCount() == 1);
    publish(4);
    render();
    assert(out.front() == 1234);
}

static void testStaleGenerationSkipped() {
    SharedRingBuffer ring;
    initAudioRing(ring);

    AudioPcmPipeline pipeline;
    pipeline.setGenerationId(5);

    std::array<int16_t, 64 * kAudioChannels> stale{};
    stale.fill(777);
    assert(ring.writePacket(stale.data(), static_cast<uint32_t>(stale.size() * sizeof(int16_t)), 4)
           == RingResult::Ok);

    std::array<int16_t, 64 * kAudioChannels> fresh{};
    fresh.fill(999);
    assert(ring.writePacket(fresh.data(), static_cast<uint32_t>(fresh.size() * sizeof(int16_t)), 5)
           == RingResult::Ok);

    std::array<int16_t, 64 * kAudioChannels> out{};
    pipeline.render(ring, out.data(), 64);
    for (int16_t s : out) {
        assert(s == 999);
    }
    assert(pipeline.underrunCount() == 0);
}

static void testGenerationChangeDiscardsPartiallyRenderedPacket() {
    SharedRingBuffer ring;
    initAudioRing(ring);
    AudioPcmPipeline pipeline;
    pipeline.setGenerationId(1);
    const std::array<int16_t, 4> previous{11, 11, 11, 11};
    const std::array<int16_t, 2> current{22, 22};
    std::array<int16_t, 2> out{};
    assert(ring.writePacket(previous.data(), sizeof(previous), 1) == RingResult::Ok);
    pipeline.render(ring, out.data(), 1);
    assert(out[0] == 11 && out[1] == 11);

    pipeline.setGenerationId(2);
    assert(ring.writePacket(current.data(), sizeof(current), 2) == RingResult::Ok);
    pipeline.render(ring, out.data(), 1);
    assert(out[0] == 22 && out[1] == 22);
}

static void testOffsetClampingAndPositiveDelay() {
    assert(AudioPcmPipeline::clampOffsetMs(-500) == kMinAudioOffsetMs);
    assert(AudioPcmPipeline::clampOffsetMs(500) == kMaxAudioOffsetMs);
    assert(AudioPcmPipeline::clampOffsetMs(25) == 25);

    SharedRingBuffer ring;
    initAudioRing(ring);

    AudioPcmPipeline pipeline;
    pipeline.setGenerationId(1);
    pipeline.setTargetOffsetMs(500);
    assert(pipeline.targetOffsetMs() == kMaxAudioOffsetMs);
    pipeline.setTargetOffsetMs(10); // +10 ms = +480 frames of silence delay

    std::array<int16_t, 200 * kAudioChannels> pcm{};
    pcm.fill(1234);
    assert(ring.writePacket(pcm.data(), static_cast<uint32_t>(pcm.size() * sizeof(int16_t)), 1)
           == RingResult::Ok);

    constexpr int32_t delayFrames = 10 * kAudioFramesPerMs; // 480
    std::vector<int16_t> out(static_cast<size_t>(delayFrames + 200) * kAudioChannels, -1);
    pipeline.render(ring, out.data(), delayFrames + 200);

    for (size_t i = 0; i < static_cast<size_t>(delayFrames) * kAudioChannels; ++i) {
        assert(out[i] == 0);
    }
    for (size_t i = static_cast<size_t>(delayFrames) * kAudioChannels; i < out.size(); ++i) {
        assert(out[i] == 1234);
    }
    assert(pipeline.underrunCount() == 0);
}

static void testNegativeOffsetAdvancesStream() {
    SharedRingBuffer ring;
    initAudioRing(ring);

    AudioPcmPipeline pipeline;
    pipeline.setGenerationId(1);
    pipeline.setTargetOffsetMs(-5); // -5 ms = drop 240 frames

    std::array<int16_t, 400 * kAudioChannels> pcm{};
    for (int32_t f = 0; f < 400; ++f) {
        pcm[static_cast<size_t>(f) * 2] = static_cast<int16_t>(f);
        pcm[static_cast<size_t>(f) * 2 + 1] = static_cast<int16_t>(f);
    }
    assert(ring.writePacket(pcm.data(), static_cast<uint32_t>(pcm.size() * sizeof(int16_t)), 1)
           == RingResult::Ok);

    constexpr int32_t dropFrames = 5 * kAudioFramesPerMs; // 240
    constexpr int32_t remainFrames = 400 - dropFrames;   // 160
    std::array<int16_t, remainFrames * kAudioChannels> out{};
    pipeline.render(ring, out.data(), remainFrames);

    for (int32_t f = 0; f < remainFrames; ++f) {
        assert(out[static_cast<size_t>(f) * 2] == static_cast<int16_t>(dropFrames + f));
        assert(out[static_cast<size_t>(f) * 2 + 1] == static_cast<int16_t>(dropFrames + f));
    }
    assert(pipeline.underrunCount() == 0);
}

static void testMuteAndDuck() {
    SharedRingBuffer ring;
    initAudioRing(ring);

    AudioPcmPipeline pipeline;
    pipeline.setGenerationId(1);

    std::array<int16_t, 64 * kAudioChannels> pcm{};
    pcm.fill(400);
    assert(ring.writePacket(pcm.data(), static_cast<uint32_t>(pcm.size() * sizeof(int16_t)), 1)
           == RingResult::Ok);

    std::array<int16_t, 64 * kAudioChannels> out{};
    out.fill(-1);

    // Muted: outputs silence without consuming ring packet.
    pipeline.setMuted(true);
    pipeline.render(ring, out.data(), 64);
    for (int16_t s : out) assert(s == 0);
    assert(pipeline.underrunCount() == 0);

    // Unmuted + ducked: consumes packet attenuated by >> 2 (400 >> 2 == 100).
    pipeline.setMuted(false);
    pipeline.setDucked(true);
    pipeline.render(ring, out.data(), 64);
    for (int16_t s : out) assert(s == 100);
    assert(pipeline.underrunCount() == 0);
}

int main() {
    static_assert(kAudioBytesPerFrame == 4, "Expected 16-bit stereo PCM");
    testPacketBoundariesAndStreaming();
    testUnderrunOutputsSilence();
    testPrebufferAbsorbsFragmentedArrivalAndRebuffersAfterUnderrun();
    testStaleGenerationSkipped();
    testGenerationChangeDiscardsPartiallyRenderedPacket();
    testOffsetClampingAndPositiveDelay();
    testNegativeOffsetAdvancesStream();
    testMuteAndDuck();
    std::puts("Audio PCM pipeline host tests passed: packet boundaries, underrun silence, stale generation, bounded offset, mute and duck.");
}
