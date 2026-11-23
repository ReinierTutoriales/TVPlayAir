#include "session_producer.hpp"

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"
#include "EndianPortable.h"

#include <jni.h>
#include <memory>
#include <mutex>
#include <vector>

namespace {
std::mutex gVideoProducerMutex;
std::mutex gAlacMutex;
tvplayair::SessionProducer gSessionProducer;
std::unique_ptr<ALACDecoder> gAlacDecoder;
uint32_t gAlacFramesPerPacket = 352;
} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeWriteVideoFrame(
    JNIEnv* env,
    jobject,
    jobject directBuffer,
    jint size,
    jlong ptsUs,
    jboolean isHevc,
    jboolean isLengthPrefixed,
    jint generationId,
    jint extraFlags) {
    if (!directBuffer || size <= 0) return -1;
    auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (!src || capacity < size) return -1;

    std::lock_guard<std::mutex> lock(gVideoProducerMutex);
    const tvplayair::RingResult res = gSessionProducer.writeVideoFrame(
        tvplayair::producerVideoRing(),
        static_cast<uint64_t>(ptsUs),
        src,
        static_cast<size_t>(size),
        isHevc == JNI_TRUE,
        isLengthPrefixed == JNI_TRUE,
        static_cast<uint32_t>(generationId),
        static_cast<uint32_t>(extraFlags));
    return static_cast<jint>(res);
}

JNIEXPORT jbyteArray JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeConvertMirrorCodecConfig(
    JNIEnv* env, jobject, jobject directBuffer, jint size, jboolean isHevc) {
    // Codec setup travels over Binder. Keep the rare control payload bounded.
    constexpr size_t kMaxCodecConfigBytes = 64 * 1024;
    if (!directBuffer || size <= 0 || size > static_cast<jint>(kMaxCodecConfigBytes)) return nullptr;
    auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    if (!src || env->GetDirectBufferCapacity(directBuffer) < size) return nullptr;

    std::vector<uint8_t> annexB(std::min(kMaxCodecConfigBytes, static_cast<size_t>(size) * 2));
    const auto length = static_cast<jsize>(tvplayair::convertDecoderConfigToAnnexB(
        src, static_cast<size_t>(size), annexB.data(), annexB.size(), isHevc == JNI_TRUE));
    if (length <= 0) return nullptr;
    jbyteArray output = env->NewByteArray(length);
    if (output) {
        env->SetByteArrayRegion(output, 0, length, reinterpret_cast<const jbyte*>(annexB.data()));
    }
    return output;
}

JNIEXPORT jint JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeWriteAudioPcm(
    JNIEnv* env,
    jobject,
    jobject directBuffer,
    jint byteCount,
    jint generationId,
    jlong senderTimeUs) {
    if (!directBuffer || byteCount <= 0) return 0;
    auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (!src || capacity < byteCount) return 0;

    return static_cast<jint>(gSessionProducer.writeAudioPcm(
        tvplayair::producerAudioRing(),
        src,
        static_cast<size_t>(byteCount),
        static_cast<uint32_t>(generationId), static_cast<int64_t>(senderTimeUs)));
}

JNIEXPORT jlong JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeGetVideoDroppedPackets(
    JNIEnv*, jobject) {
    return static_cast<jlong>(tvplayair::producerVideoRing().droppedPackets());
}

JNIEXPORT jlong JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeGetAudioDroppedPackets(
    JNIEnv*, jobject) {
    return static_cast<jlong>(tvplayair::producerAudioRing().droppedPackets());
}

JNIEXPORT jboolean JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeConfigureAlac(
    JNIEnv*, jobject, jint samplesPerFrame, jint sampleRate) {
    std::lock_guard<std::mutex> lock(gAlacMutex);
    // A failed reconfiguration must not leave the previous decoder active.
    gAlacDecoder.reset();
    gAlacFramesPerPacket = 0;
    const uint32_t frames = samplesPerFrame > 0 ? static_cast<uint32_t>(samplesPerFrame) : 352u;
    const uint32_t rate = sampleRate > 0 ? static_cast<uint32_t>(sampleRate) : 44100u;

    ALACSpecificConfig cookie{};
    cookie.frameLength = Swap32NtoB(frames);
    cookie.compatibleVersion = 0;
    cookie.bitDepth = 16;
    cookie.pb = 40;
    cookie.mb = 10;
    cookie.kb = 14;
    cookie.numChannels = 2;
    cookie.maxRun = Swap16NtoB(255);
    cookie.maxFrameBytes = 0;
    cookie.avgBitRate = 0;
    cookie.sampleRate = Swap32NtoB(rate);

    auto decoder = std::make_unique<ALACDecoder>();
    if (decoder->Init(&cookie, sizeof(cookie)) != ALAC_noErr) {
        return JNI_FALSE;
    }
    gAlacFramesPerPacket = frames;
    gAlacDecoder = std::move(decoder);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeDecodeAlacToPcm(
    JNIEnv* env,
    jobject,
    jbyteArray alacPacket,
    jint offset,
    jint length,
    jshortArray outPcmShorts) {
    if (!alacPacket || !outPcmShorts || offset < 0 || length <= 0) return 0;
    const jsize packetLen = env->GetArrayLength(alacPacket);
    const jsize outCapShorts = env->GetArrayLength(outPcmShorts);
    if (offset > packetLen || length > packetLen - offset || outCapShorts < 2) return 0;

    std::lock_guard<std::mutex> lock(gAlacMutex);
    if (!gAlacDecoder) return 0;

    const uint32_t maxFrames = std::min(
        gAlacFramesPerPacket,
        static_cast<uint32_t>(outCapShorts / 2));

    jbyte* inBytes = env->GetByteArrayElements(alacPacket, nullptr);
    if (!inBytes) return 0;
    jshort* outShorts = env->GetShortArrayElements(outPcmShorts, nullptr);
    if (!outShorts) {
        env->ReleaseByteArrayElements(alacPacket, inBytes, JNI_ABORT);
        return 0;
    }

    BitBuffer bits{};
    BitBufferInit(
        &bits,
        reinterpret_cast<uint8_t*>(inBytes + offset),
        static_cast<uint32_t>(length));
    uint32_t outFrames = 0;
    const int32_t status = gAlacDecoder->Decode(
        &bits,
        reinterpret_cast<uint8_t*>(outShorts),
        maxFrames,
        2,
        &outFrames);

    env->ReleaseShortArrayElements(outPcmShorts, outShorts, 0);
    env->ReleaseByteArrayElements(alacPacket, inBytes, JNI_ABORT);
    return (status == ALAC_noErr && outFrames <= maxFrames) ? static_cast<jint>(outFrames) : 0;
}

} // extern "C"
