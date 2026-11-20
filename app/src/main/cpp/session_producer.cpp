#include "session_producer.hpp"

#include <jni.h>
#include <mutex>

namespace {
std::mutex gVideoProducerMutex;
tvplayair::SessionProducer gSessionProducer;
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

JNIEXPORT jint JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeWriteAudioPcm(
    JNIEnv* env,
    jobject,
    jobject directBuffer,
    jint byteCount,
    jint generationId) {
    if (!directBuffer || byteCount <= 0) return 0;
    auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (!src || capacity < byteCount) return 0;

    return static_cast<jint>(gSessionProducer.writeAudioPcm(
        tvplayair::producerAudioRing(),
        src,
        static_cast<size_t>(byteCount),
        static_cast<uint32_t>(generationId)));
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

} // extern "C"
