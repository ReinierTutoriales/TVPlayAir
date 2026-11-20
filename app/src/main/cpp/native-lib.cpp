#include <jni.h>
#include <android/log.h>
#include <android/sharedmem.h>
#include <cerrno>
#include <unistd.h>

#include "oboe_consumer.hpp"
#include "shared_ring_buffer.hpp"

namespace {
tvplayair::SharedRingBuffer gProducerVideo;
tvplayair::SharedRingBuffer gProducerAudio;
tvplayair::SharedRingBuffer gConsumerVideo;
tvplayair::SharedRingBuffer gConsumerAudio;

int createProducerBuffer(const char* name, size_t bytes, uint32_t capacity,
                         uint32_t payloadCapacity, tvplayair::SharedRingBuffer& ring) {
    const int fd = ASharedMemory_create(name, bytes);
    if (fd < 0) return -1;
    const int mappingFd = ::dup(fd);
    if (mappingFd < 0 || !ring.mapFd(mappingFd, bytes, true, capacity, payloadCapacity)) {
        ::close(fd);
        return -1;
    }
    return fd; // caller adopts this descriptor; ring owns only its mmap
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeCreateProducerBuffer(
    JNIEnv*, jobject, jint kind) {
    if (kind == 0) return createProducerBuffer("tvplayair-video", 16 * 1024 * 1024,
                                                128, 64 * 1024, gProducerVideo);
    if (kind == 1) return createProducerBuffer("tvplayair-audio", 512 * 1024,
                                                64, 4 * 1024, gProducerAudio);
    return -1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeMapConsumerBuffer(
    JNIEnv*, jobject, jint kind, jint fd, jlong bytes) {
    bool mapped = false;
    if (kind == 0) {
        mapped = gConsumerVideo.mapFd(fd, static_cast<size_t>(bytes), false);
    } else if (kind == 1) {
        mapped = gConsumerAudio.mapFd(fd, static_cast<size_t>(bytes), false);
    } else {
        ::close(fd);
    }
    if (!mapped) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            "TVPlayAirIPC",
            "nativeMapConsumerBuffer failed: kind=%d bytes=%lld errno=%d",
            kind,
            static_cast<long long>(bytes),
            errno);
    }
    return mapped ? JNI_TRUE : JNI_FALSE;
}

namespace tvplayair {
SharedRingBuffer& producerVideoRing() {
    return gProducerVideo;
}

SharedRingBuffer& producerAudioRing() {
    return gProducerAudio;
}

SharedRingBuffer& consumerAudioRing() {
    return gConsumerAudio;
}
} // namespace tvplayair

extern "C" JNIEXPORT void JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeDetachConsumerBuffers(
    JNIEnv*, jobject) {
    tvplayair::oboeConsumer().stop();
    gConsumerVideo.detach();
    gConsumerAudio.detach();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeReadVideoPacket(
    JNIEnv* env, jobject, jobject directBuffer, jint generationId) {
    if (!directBuffer) return -2;
    void* dst = env->GetDirectBufferAddress(directBuffer);
    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (!dst || capacity <= 0) return -2;

    uint32_t outSize = 0;
    const tvplayair::RingResult result = gConsumerVideo.readPacket(
        dst, static_cast<uint32_t>(capacity), &outSize, static_cast<uint32_t>(generationId));
    if (result == tvplayair::RingResult::Ok) return static_cast<jint>(outSize);
    if (result == tvplayair::RingResult::Empty) return 0;
    if (result == tvplayair::RingResult::StaleGeneration) return -1;
    return -2;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeDestroyProducerBuffers(
    JNIEnv*, jobject) {
    gProducerVideo.detach();
    gProducerAudio.detach();
}
