#include "fairplay_session.hpp"

#include <array>
#include <jni.h>
#include <mutex>

namespace {
std::mutex gFairPlayMutex;
tvplayair::FairPlayV3Session gFairPlaySession;
} // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_tvplayair_receiver_session_FairPlayNative_nativeSetup(
    JNIEnv* env, jobject, jbyteArray reqArray) {
    if (!reqArray || env->GetArrayLength(reqArray) != tvplayair::FairPlayV3Session::kSetupReqBytes) {
        return nullptr;
    }
    std::array<uint8_t, tvplayair::FairPlayV3Session::kSetupReqBytes> req{};
    env->GetByteArrayRegion(reqArray, 0, req.size(), reinterpret_cast<jbyte*>(req.data()));

    std::array<uint8_t, tvplayair::FairPlayV3Session::kSetupResBytes> res{};
    {
        std::lock_guard<std::mutex> lock(gFairPlayMutex);
        if (!gFairPlaySession.setup(req.data(), req.size(), res.data(), res.size())) {
            return nullptr;
        }
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
    JNIEnv* env, jobject, jbyteArray reqArray) {
    if (!reqArray ||
        env->GetArrayLength(reqArray) != tvplayair::FairPlayV3Session::kHandshakeReqBytes) {
        return nullptr;
    }
    std::array<uint8_t, tvplayair::FairPlayV3Session::kHandshakeReqBytes> req{};
    env->GetByteArrayRegion(reqArray, 0, req.size(), reinterpret_cast<jbyte*>(req.data()));

    std::array<uint8_t, tvplayair::FairPlayV3Session::kHandshakeResBytes> res{};
    {
        std::lock_guard<std::mutex> lock(gFairPlayMutex);
        if (!gFairPlaySession.handshake(req.data(), req.size(), res.data(), res.size())) {
            return nullptr;
        }
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
    JNIEnv* env, jobject, jbyteArray ekeyArray) {
    if (!ekeyArray ||
        env->GetArrayLength(ekeyArray) != tvplayair::FairPlayV3Session::kEncryptedKeyBytes) {
        return nullptr;
    }
    std::array<uint8_t, tvplayair::FairPlayV3Session::kEncryptedKeyBytes> ekey{};
    env->GetByteArrayRegion(ekeyArray, 0, ekey.size(), reinterpret_cast<jbyte*>(ekey.data()));

    std::array<uint8_t, tvplayair::FairPlayV3Session::kAesKeyBytes> key{};
    {
        std::lock_guard<std::mutex> lock(gFairPlayMutex);
        if (!gFairPlaySession.decryptKey(ekey.data(), ekey.size(), key.data(), key.size())) {
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

JNIEXPORT void JNICALL
Java_com_tvplayair_receiver_session_FairPlayNative_nativeReset(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gFairPlayMutex);
    gFairPlaySession.reset();
}

} // extern "C"
