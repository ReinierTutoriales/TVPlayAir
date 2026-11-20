#include <jni.h>
#include <oboe/Oboe.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_tvplayair_receiver_MainActivity_nativeBuildInfo(JNIEnv* env, jobject) {
    oboe::AudioStreamBuilder builder;
    builder.setSampleRate(48000);
    return env->NewStringUTF("TVPlayAir native baseline");
}
