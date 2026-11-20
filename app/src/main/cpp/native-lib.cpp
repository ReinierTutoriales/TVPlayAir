#include <jni.h>
#include <android/sharedmem.h>
#include <oboe/Oboe.h>
#include <unistd.h>

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
    if (kind == 0) return gConsumerVideo.mapFd(fd, static_cast<size_t>(bytes), false);
    if (kind == 1) return gConsumerAudio.mapFd(fd, static_cast<size_t>(bytes), false);
    ::close(fd);
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeDetachConsumerBuffers(
    JNIEnv*, jobject) {
    gConsumerVideo.detach();
    gConsumerAudio.detach();
}

extern "C" JNIEXPORT void JNICALL
Java_com_tvplayair_receiver_session_NativeSharedMemory_nativeDestroyProducerBuffers(
    JNIEnv*, jobject) {
    gProducerVideo.detach();
    gProducerAudio.detach();
}
