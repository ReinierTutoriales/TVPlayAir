#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

#include <oboe/Oboe.h>

#include "audio_pcm_pipeline.hpp"
#include "shared_ring_buffer.hpp"

namespace tvplayair {

SharedRingBuffer& consumerAudioRing();

class OboeConsumer final : public oboe::AudioStreamDataCallback,
                           public oboe::AudioStreamErrorCallback {
public:
    explicit OboeConsumer(SharedRingBuffer& ring);
    ~OboeConsumer() override;

    OboeConsumer(const OboeConsumer&) = delete;
    OboeConsumer& operator=(const OboeConsumer&) = delete;

    bool start(uint32_t generationId, bool discardInterruptedAudio);
    void stop();

    void setGenerationId(uint32_t generationId);
    void setOffsetMs(int32_t offsetMs);
    int32_t offsetMs() const;
    void setMuted(bool muted);
    void setDucked(bool ducked);

    bool isRunning() const;
    int64_t senderClockOffsetNs();

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames) override;

    void onErrorBeforeClose(oboe::AudioStream* oboeStream, oboe::Result error) override;
    void onErrorAfterClose(oboe::AudioStream* oboeStream, oboe::Result error) override;

private:
    bool openAndStartLocked();
    oboe::Result openWithFallbackLocked(std::shared_ptr<oboe::AudioStream>& outStream);

    SharedRingBuffer& ring_;
    AudioPcmPipeline pipeline_;

    mutable std::mutex streamMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;

    std::atomic<bool> shouldRun_{false};
    std::atomic<bool> running_{false};
    bool preferSharedMode_ = false;
    // Only the callback writes the anchor, except initialization before requestStart().
    int64_t submittedFrames_ = 0;
    std::atomic<uint32_t> anchorSequence_{0};
    std::atomic<int64_t> anchorSenderTimeUs_{0};
    std::atomic<int64_t> anchorFrame_{0};
    std::atomic<uint32_t> anchorGeneration_{0};
};

OboeConsumer& oboeConsumer();

} // namespace tvplayair
