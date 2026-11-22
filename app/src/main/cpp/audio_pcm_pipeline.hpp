#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>

#include "shared_ring_buffer.hpp"

namespace tvplayair {

constexpr int32_t kAudioSampleRate = 48000;
constexpr int32_t kAudioChannels = 2;
constexpr size_t kAudioBytesPerFrame = sizeof(int16_t) * kAudioChannels;
constexpr int32_t kAudioFramesPerMs = kAudioSampleRate / 1000;
constexpr int32_t kMinAudioOffsetMs = -200;
constexpr int32_t kMaxAudioOffsetMs = 200;
constexpr uint32_t kMaxAudioPacketBytes = 4096;
constexpr uint32_t kMaxDrainAttemptsPerCallback = 64;

// Lock-free, allocation-free PCM 16-bit/48kHz stereo reader for Oboe callbacks.
// render() must only be called from the single real-time audio callback thread;
// reset() must only be called while the audio stream is stopped.
class AudioPcmPipeline final {
public:
    static constexpr int32_t clampOffsetMs(int32_t offsetMs) {
        return offsetMs < kMinAudioOffsetMs
            ? kMinAudioOffsetMs
            : (offsetMs > kMaxAudioOffsetMs ? kMaxAudioOffsetMs : offsetMs);
    }

    void reset() {
        packetFrameOffset_ = 0;
        packetFrameCount_ = 0;
        appliedOffsetFrames_ = 0;
    }

    void setTargetOffsetMs(int32_t offsetMs) {
        targetOffsetMs_.store(clampOffsetMs(offsetMs), std::memory_order_relaxed);
    }

    int32_t targetOffsetMs() const {
        return targetOffsetMs_.load(std::memory_order_relaxed);
    }

    void setGenerationId(uint32_t generationId) {
        generationId_.store(generationId, std::memory_order_relaxed);
    }

    uint32_t generationId() const {
        return generationId_.load(std::memory_order_relaxed);
    }

    void setMuted(bool muted) {
        muted_.store(muted, std::memory_order_relaxed);
    }

    bool isMuted() const {
        return muted_.load(std::memory_order_relaxed);
    }

    void setDucked(bool ducked) {
        ducked_.store(ducked, std::memory_order_relaxed);
    }

    bool isDucked() const {
        return ducked_.load(std::memory_order_relaxed);
    }

    uint64_t underrunCount() const {
        return underrunCount_.load(std::memory_order_relaxed);
    }

    void render(SharedRingBuffer& ring, int16_t* out, int32_t numFrames) {
        if (!out || numFrames <= 0) return;

        if (muted_.load(std::memory_order_relaxed)) {
            std::memset(out, 0, static_cast<size_t>(numFrames) * kAudioBytesPerFrame);
            return;
        }

        const uint32_t expectedGen = generationId_.load(std::memory_order_relaxed);
        if (packetGenerationId_ != expectedGen) {
            packetFrameOffset_ = 0;
            packetFrameCount_ = 0;
        }
        const bool duck = ducked_.load(std::memory_order_relaxed);
        const int32_t targetFrames =
            targetOffsetMs_.load(std::memory_order_relaxed) * kAudioFramesPerMs;
        const int32_t deltaFrames = targetFrames - appliedOffsetFrames_;

        int32_t outFrame = 0;

        if (deltaFrames > 0) {
            const int32_t silenceFrames = std::min(deltaFrames, numFrames);
            std::memset(out, 0, static_cast<size_t>(silenceFrames) * kAudioBytesPerFrame);
            outFrame += silenceFrames;
            appliedOffsetFrames_ += silenceFrames;
        } else if (deltaFrames < 0) {
            int32_t framesToDrop = -deltaFrames;
            while (framesToDrop > 0) {
                if (packetFrameOffset_ < packetFrameCount_) {
                    const uint32_t avail = packetFrameCount_ - packetFrameOffset_;
                    const uint32_t drop = std::min(avail, static_cast<uint32_t>(framesToDrop));
                    packetFrameOffset_ += drop;
                    framesToDrop -= static_cast<int32_t>(drop);
                    appliedOffsetFrames_ -= static_cast<int32_t>(drop);
                } else if (!refillPacket(ring, expectedGen)) {
                    break;
                }
            }
        }

        bool hadUnderrun = false;
        while (outFrame < numFrames) {
            if (packetFrameOffset_ < packetFrameCount_) {
                const uint32_t avail = packetFrameCount_ - packetFrameOffset_;
                const uint32_t copyFrames =
                    std::min(avail, static_cast<uint32_t>(numFrames - outFrame));
                const int16_t* src = packetBuf_.data() +
                    static_cast<size_t>(packetFrameOffset_) * kAudioChannels;
                int16_t* dst = out + static_cast<size_t>(outFrame) * kAudioChannels;
                const size_t sampleCount = static_cast<size_t>(copyFrames) * kAudioChannels;
                if (duck) {
                    for (size_t i = 0; i < sampleCount; ++i) {
                        dst[i] = static_cast<int16_t>(src[i] >> 2);
                    }
                } else {
                    std::memcpy(dst, src, sampleCount * sizeof(int16_t));
                }
                packetFrameOffset_ += copyFrames;
                outFrame += static_cast<int32_t>(copyFrames);
            } else if (!refillPacket(ring, expectedGen)) {
                const size_t remainingFrames = static_cast<size_t>(numFrames - outFrame);
                std::memset(out + static_cast<size_t>(outFrame) * kAudioChannels,
                            0,
                            remainingFrames * kAudioBytesPerFrame);
                hadUnderrun = true;
                break;
            }
        }

        if (hadUnderrun) {
            underrunCount_.fetch_add(1, std::memory_order_relaxed);
        }
    }

private:
    bool refillPacket(SharedRingBuffer& ring, uint32_t expectedGen) {
        for (uint32_t attempt = 0; attempt < kMaxDrainAttemptsPerCallback; ++attempt) {
            uint32_t outSize = 0;
            const RingResult res = ring.readPacket(
                packetBuf_.data(), kMaxAudioPacketBytes, &outSize, expectedGen);
            if (res == RingResult::Ok) {
                const uint32_t frames = static_cast<uint32_t>(outSize / kAudioBytesPerFrame);
                if (frames > 0) {
                    packetFrameOffset_ = 0;
                    packetFrameCount_ = frames;
                    packetGenerationId_ = expectedGen;
                    return true;
                }
                continue;
            }
            if (res == RingResult::StaleGeneration || res == RingResult::BufferTooSmall) {
                continue;
            }
            return false;
        }
        return false;
    }

    std::array<int16_t, kMaxAudioPacketBytes / sizeof(int16_t)> packetBuf_{};
    uint32_t packetFrameOffset_ = 0;
    uint32_t packetFrameCount_ = 0;
    uint32_t packetGenerationId_ = 0;
    int32_t appliedOffsetFrames_ = 0;

    std::atomic<int32_t> targetOffsetMs_{0};
    std::atomic<uint32_t> generationId_{0};
    std::atomic<bool> muted_{false};
    std::atomic<bool> ducked_{false};
    std::atomic<uint64_t> underrunCount_{0};
};

} // namespace tvplayair
