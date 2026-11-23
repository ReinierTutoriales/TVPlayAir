#include <time.h>
#include "oboe_consumer.hpp"

#include <android/log.h>
#include <jni.h>

namespace {
constexpr const char* kLogTag = "TVPlayAirOboe";
} // namespace

namespace tvplayair {

OboeConsumer::OboeConsumer(SharedRingBuffer& ring) : ring_(ring) {}

OboeConsumer::~OboeConsumer() {
    stop();
}

bool OboeConsumer::start(uint32_t generationId, bool discardInterruptedAudio) {
    std::lock_guard<std::mutex> lock(streamMutex_);
    pipeline_.setGenerationId(generationId);
    if (stream_ && running_.load(std::memory_order_acquire)) {
        return true;
    }
    if (stream_) {
        auto staleStream = std::move(stream_);
        running_.store(false, std::memory_order_release);
        staleStream->requestStop();
        staleStream->close();
    }
    pipeline_.reset();
    if (discardInterruptedAudio) {
        // The producer continued while ExoPlayer owned the output. Resume at live PCM.
        ring_.discardPending();
    }
    const uint32_t discarded = ring_.discardOlderGenerations(generationId);
    if (discarded > 0) {
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                            "Discarded %u stale PCM packets before opening generation %u",
                            discarded, generationId);
    }
    shouldRun_.store(true, std::memory_order_release);
    if (!openAndStartLocked()) {
        shouldRun_.store(false, std::memory_order_release);
        return false;
    }
    return true;
}

void OboeConsumer::stop() {
    shouldRun_.store(false, std::memory_order_release);
    std::shared_ptr<oboe::AudioStream> localStream;
    {
        std::lock_guard<std::mutex> lock(streamMutex_);
        localStream = std::move(stream_);
        running_.store(false, std::memory_order_release);
    }
    // Close outside streamMutex_ to avoid deadlocking with onErrorAfterClose().
    if (localStream) {
        localStream->requestStop();
        localStream->close();
    }
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!stream_ && !shouldRun_.load(std::memory_order_acquire)) {
        if (localStream) {
            __android_log_print(
                ANDROID_LOG_INFO, kLogTag,
                "PCM process totals closingGeneration=%u underrunCallbacks=%llu underrunFrames=%llu",
                pipeline_.generationId(),
                static_cast<unsigned long long>(pipeline_.underrunCount()),
                static_cast<unsigned long long>(pipeline_.underrunFrames()));
        }
        pipeline_.reset();
    }
}

void OboeConsumer::setGenerationId(uint32_t generationId) {
    pipeline_.setGenerationId(generationId);
}

void OboeConsumer::setOffsetMs(int32_t offsetMs) {
    pipeline_.setTargetOffsetMs(offsetMs);
}

int32_t OboeConsumer::offsetMs() const {
    return pipeline_.targetOffsetMs();
}

void OboeConsumer::setMuted(bool muted) {
    pipeline_.setMuted(muted);
}

void OboeConsumer::setDucked(bool ducked) {
    pipeline_.setDucked(ducked);
}

bool OboeConsumer::isRunning() const {
    return running_.load(std::memory_order_acquire);
}

oboe::Result OboeConsumer::openWithFallbackLocked(
    std::shared_ptr<oboe::AudioStream>& outStream) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(preferSharedMode_ ? oboe::SharingMode::Shared : oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::I16)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(kAudioSampleRate)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Movie)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    oboe::Result result = builder.openStream(outStream);
    if (result != oboe::Result::OK && !preferSharedMode_) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "Exclusive LowLatency openStream failed (%s); switching to SharingMode::Shared",
            oboe::convertToText(result));
        preferSharedMode_ = true;
        outStream.reset();
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(outStream);
    }
    return result;
}

bool OboeConsumer::openAndStartLocked() {
    std::shared_ptr<oboe::AudioStream> newStream;
    const oboe::Result openResult = openWithFallbackLocked(newStream);
    if (openResult != oboe::Result::OK || !newStream) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kLogTag,
            "Failed to open Oboe stream after Shared fallback: %s",
            oboe::convertToText(openResult));
        running_.store(false, std::memory_order_release);
        return false;
    }

    const int32_t framesPerBurst = newStream->getFramesPerBurst();
    if (framesPerBurst > 0) {
        const auto bufResult = newStream->setBufferSizeInFrames(framesPerBurst * 2);
        if (!bufResult) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "setBufferSizeInFrames(%d) failed: %s",
                framesPerBurst * 2,
                oboe::convertToText(bufResult.error()));
        }
    }

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "Oboe stream opened: api=%s sharing=%s perf=%s rate=%d ch=%d fmt=%s "
        "burst=%d bufSize=%d bufCap=%d deviceId=%d",
        oboe::convertToText(newStream->getAudioApi()),
        oboe::convertToText(newStream->getSharingMode()),
        oboe::convertToText(newStream->getPerformanceMode()),
        newStream->getSampleRate(),
        newStream->getChannelCount(),
        oboe::convertToText(newStream->getFormat()),
        framesPerBurst,
        newStream->getBufferSizeInFrames(),
        newStream->getBufferCapacityInFrames(),
        newStream->getDeviceId());

    submittedFrames_ = 0;
    anchorSenderTimeUs_.store(0);
    const oboe::Result startResult = newStream->requestStart();
    if (startResult != oboe::Result::OK) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kLogTag,
            "Oboe requestStart failed: %s",
            oboe::convertToText(startResult));
        newStream->close();
        running_.store(false, std::memory_order_release);
        return false;
    }

    stream_ = std::move(newStream);
    running_.store(true, std::memory_order_release);
    return true;
}

oboe::DataCallbackResult OboeConsumer::onAudioReady(
    oboe::AudioStream* /* audioStream */,
    void* audioData,
    int32_t numFrames) {
    const auto timing = pipeline_.render(ring_, static_cast<int16_t*>(audioData), numFrames);
    anchorSequence_.fetch_add(1);
    anchorSenderTimeUs_.store(timing.senderTimeUs);
    anchorFrame_.store(submittedFrames_ + timing.outputFrame);
    anchorGeneration_.store(timing.generation);
    anchorSequence_.fetch_add(1);
    submittedFrames_ += numFrames;
    return oboe::DataCallbackResult::Continue;
}

int64_t OboeConsumer::senderClockOffsetNs() {
    const std::unique_lock<std::mutex> lock(streamMutex_, std::try_to_lock);
    if (!lock.owns_lock() || !stream_ || !isRunning() ||
        stream_->getSampleRate() != kAudioSampleRate) return INT64_MIN;

    const uint32_t before = anchorSequence_.load();
    if ((before & 1U) != 0U) return INT64_MIN;
    const int64_t senderTimeUs = anchorSenderTimeUs_.load();
    const int64_t frame = anchorFrame_.load();
    const uint32_t generation = anchorGeneration_.load();
    if (before != anchorSequence_.load() || senderTimeUs <= 0 ||
        generation != pipeline_.generationId()) return INT64_MIN;

    int64_t hardwareFrame = 0;
    int64_t hardwareNs = 0;
    if (stream_->getTimestamp(CLOCK_MONOTONIC, &hardwareFrame, &hardwareNs) != oboe::Result::OK ||
        hardwareFrame < 0 || hardwareNs <= 0 || generation != pipeline_.generationId()) return INT64_MIN;
    const int64_t frames = frame - hardwareFrame;
    if (frames < -kAudioSampleRate / 2 || frames > kAudioSampleRate / 2) return INT64_MIN;
    return hardwareNs + frames * INT64_C(1000000000) / kAudioSampleRate - senderTimeUs * 1000;
}

void OboeConsumer::onErrorBeforeClose(oboe::AudioStream* /* oboeStream */, oboe::Result error) {
    running_.store(false, std::memory_order_release);
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "Oboe stream error before close: %s",
        oboe::convertToText(error));
}

void OboeConsumer::onErrorAfterClose(oboe::AudioStream* oboeStream, oboe::Result error) {
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "Oboe stream error after close (%s); attempting route recovery",
        oboe::convertToText(error));

    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_ && stream_.get() != oboeStream) {
        return;
    }
    stream_.reset();
    if (!shouldRun_.load(std::memory_order_acquire)) {
        running_.store(false, std::memory_order_release);
        return;
    }

    pipeline_.reset();
    if (openAndStartLocked()) {
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "Oboe stream recovered successfully");
    } else {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Oboe stream recovery failed");
    }
}

OboeConsumer& oboeConsumer() {
    static OboeConsumer instance(consumerAudioRing());
    return instance;
}

} // namespace tvplayair

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_tvplayair_receiver_audio_OboeAudioConsumer_nativeStart(
    JNIEnv*, jobject, jint generationId, jboolean discardInterruptedAudio) {
    return tvplayair::oboeConsumer().start(static_cast<uint32_t>(generationId), discardInterruptedAudio == JNI_TRUE)
        ? JNI_TRUE
        : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_tvplayair_receiver_audio_OboeAudioConsumer_nativeStop(JNIEnv*, jobject) {
    tvplayair::oboeConsumer().stop();
}

JNIEXPORT void JNICALL
Java_com_tvplayair_receiver_audio_OboeAudioConsumer_nativeSetGeneration(
    JNIEnv*, jobject, jint generationId) {
    tvplayair::oboeConsumer().setGenerationId(static_cast<uint32_t>(generationId));
}

JNIEXPORT void JNICALL
Java_com_tvplayair_receiver_audio_OboeAudioConsumer_nativeSetOffsetMs(
    JNIEnv*, jobject, jint offsetMs) {
    tvplayair::oboeConsumer().setOffsetMs(static_cast<int32_t>(offsetMs));
}

JNIEXPORT jint JNICALL
Java_com_tvplayair_receiver_audio_OboeAudioConsumer_nativeGetOffsetMs(JNIEnv*, jobject) {
    return static_cast<jint>(tvplayair::oboeConsumer().offsetMs());
}

JNIEXPORT void JNICALL
Java_com_tvplayair_receiver_audio_OboeAudioConsumer_nativeSetMuted(
    JNIEnv*, jobject, jboolean muted) {
    tvplayair::oboeConsumer().setMuted(muted == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tvplayair_receiver_audio_OboeAudioConsumer_nativeSetDucked(
    JNIEnv*, jobject, jboolean ducked) {
    tvplayair::oboeConsumer().setDucked(ducked == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_tvplayair_receiver_audio_OboeAudioConsumer_nativeIsRunning(JNIEnv*, jobject) {
    return tvplayair::oboeConsumer().isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_tvplayair_receiver_audio_OboeAudioConsumer_nativeSenderClockOffsetNs(JNIEnv*, jobject) {
    return static_cast<jlong>(tvplayair::oboeConsumer().senderClockOffsetNs());
}

} // extern "C"
