// JNI bridge to the forked artoolkitX KPM tracker.
//
// Guarded by HAVE_ARX_KPM, which the CMake sets only when the third_party/artoolkitx submodule is
// checked out and the arx_kpm target builds. Without it (e.g. CI, or a fresh clone without
// `git submodule update --init`), these calls compile to safe no-ops so the app and CI are unaffected.
//
// PHASE 1: link smoke test only — create and destroy a KPM homography handle to prove KPM compiles
// and links in the NDK build. Capture/match/mosaic come in later phases.

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "POSEPROBE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef HAVE_ARX_KPM
#include <ARX/KPM/kpm.h>
#endif

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_KpmBridge_nativeKpmAvailable(JNIEnv *, jobject) {
#ifdef HAVE_ARX_KPM
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_KpmBridge_nativeKpmSmokeTest(
        JNIEnv *, jobject, jint width, jint height) {
#ifdef HAVE_ARX_KPM
    // kpmCreateHandleHomography needs no camera calibration — it works in pixel/homography space,
    // which is what the planar-mosaic fingerprint uses. If this returns non-null, KPM is linked and
    // callable in the NDK build.
    KpmHandle *h = kpmCreateHandleHomography((int) width, (int) height);
    if (h == nullptr) {
        LOGE("nativeKpmSmokeTest: kpmCreateHandleHomography returned null");
        return JNI_FALSE;
    }
    kpmDeleteHandle(&h);
    LOGI("nativeKpmSmokeTest: KPM handle created and destroyed OK (%dx%d)", width, height);
    return JNI_TRUE;
#else
    (void) width; (void) height;
    LOGI("nativeKpmSmokeTest: built without HAVE_ARX_KPM (submodule absent)");
    return JNI_FALSE;
#endif
}

} // extern "C"
