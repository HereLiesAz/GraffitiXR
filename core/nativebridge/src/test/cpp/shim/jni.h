#pragma once
// Host shim for the slice of <jni.h> MobileGS.cpp touches (JniThreadAttacher). Android's jni.h,
// not the desktop JDK's: AttachCurrentThread takes JNIEnv** there and void** on the desktop, so
// the real JDK header does not compile MobileGS.cpp. Tests leave gJvm null, so none of these run.
#include <cstdint>
typedef int32_t jint;
struct _JNIEnv {};
typedef _JNIEnv JNIEnv;
#define JNI_OK 0
#define JNI_EDETACHED (-2)
#define JNI_VERSION_1_6 0x00010006
struct _JavaVM {
    jint GetEnv(void**, jint) { return JNI_EDETACHED; }
    jint AttachCurrentThread(JNIEnv**, void*) { return -1; }
    jint DetachCurrentThread() { return JNI_OK; }
};
typedef _JavaVM JavaVM;
