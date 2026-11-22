#include "fairplay_session.hpp"

#include <array>
#include <jni.h>
#include <mutex>
#include <new>

namespace {
std::mutex gDecryptMutex;

tvplayair::FairPlayV3Session* sessionFrom(jlong handle) {
    return reinterpret_cast<tvplayair::FairPlayV3Session*>(handle);
}
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_tvplayair_receiver_session_FairPlayNative_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new (std::nothrow) tvplayair::FairPlayV3Session());
}

JNIEXPORT void JNICALL
Java_com_tvplayair_receiver_session_FairPlayNative_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete sessionFrom(handle);
}

JNIEXPORT jbyteArray JNICALL
Java_com_tvplayair_receiver_session_FairPlayNative_nativeSetup(
    JNIEnv* env, jobject, jlong handle, jbyteArray reqArray) {
    auto* session = sessionFrom(handle);
    if (!session || !reqArray || env->GetArrayLength(reqArray) != tvplayair::FairPlayV3Session::kSetupReqBytes) {
        return nullptr;
    }
    std::array<uint8_t, tvplayair::FairPlayV3Session::kSetupReqBytes> req{};
    env->GetByteArrayRegion(reqArray, 0, req.size(), reinterpret_cast<jbyte*>(req.data()));

    std::array<uint8_t, tvplayair::FairPlayV3Session::kSetupResBytes> res{};
    if (!session->setup(req.data(), req.size(), res.data(), res.size())) {
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(res.size()));
    if (out) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(res.size()),
                                reinterpret_cast<const jbyte*>(res.data()));
    }
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_tvplayair_receiver_session_FairPlayNative_nativeHandshake(
    JNIEnv* env, jobject, jlong handle, jbyteArray reqArray) {
    auto* session = sessionFrom(handle);
    if (!session || !reqArray ||
        env->GetArrayLength(reqArray) != tvplayair::FairPlayV3Session::kHandshakeReqBytes) {
        return nullptr;
    }
    std::array<uint8_t, tvplayair::FairPlayV3Session::kHandshakeReqBytes> req{};
    env->GetByteArrayRegion(reqArray, 0, req.size(), reinterpret_cast<jbyte*>(req.data()));

    std::array<uint8_t, tvplayair::FairPlayV3Session::kHandshakeResBytes> res{};
    if (!session->handshake(req.data(), req.size(), res.data(), res.size())) {
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(res.size()));
    if (out) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(res.size()),
                                reinterpret_cast<const jbyte*>(res.data()));
    }
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_tvplayair_receiver_session_FairPlayNative_nativeDecryptKey(
    JNIEnv* env, jobject, jlong handle, jbyteArray ekeyArray) {
    auto* session = sessionFrom(handle);
    if (!session || !ekeyArray ||
        env->GetArrayLength(ekeyArray) != tvplayair::FairPlayV3Session::kEncryptedKeyBytes) {
        return nullptr;
    }
    std::array<uint8_t, tvplayair::FairPlayV3Session::kEncryptedKeyBytes> ekey{};
    env->GetByteArrayRegion(ekeyArray, 0, ekey.size(), reinterpret_cast<jbyte*>(ekey.data()));

    std::array<uint8_t, tvplayair::FairPlayV3Session::kAesKeyBytes> key{};
    {
        std::lock_guard<std::mutex> lock(gDecryptMutex);
        if (!session->decryptKey(ekey.data(), ekey.size(), key.data(), key.size())) {
            return nullptr;
        }
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(key.size()));
    if (out) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(key.size()),
                                reinterpret_cast<const jbyte*>(key.data()));
    }
    return out;
}

} // extern "C"
