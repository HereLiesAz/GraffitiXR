// JNI bridge to the forked artoolkitX KPM tracker.
//
// ARCore remains an independent first-class pose source. This file exposes only the native KPM
// primitive used by the side-by-side :sphereslam library.
//
// Phase 2 adds a persistent planar-homography session: build/merge reference pages, match a live
// luma frame, return KPM's 3x4 projective transform + error + inlier count. Homography mode is NOT a
// calibrated metric 6-DoF world pose; calibrated pose is a later layer using camera intrinsics.

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <mutex>

#define LOG_TAG "POSEPROBE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef HAVE_ARX_KPM
#include <ARX/KPM/kpm.h>

namespace {

struct KpmSession {
    KpmHandle *handle = nullptr;
    KpmRefDataSet *atlas = nullptr;
    int frameWidth = 0;
    int frameHeight = 0;
    std::mutex mutex;
};

KpmSession *asSession(jlong value) {
    return reinterpret_cast<KpmSession *>(static_cast<intptr_t>(value));
}

bool directBuffer(JNIEnv *env, jobject buffer, jlong requiredBytes, ARUint8 **out) {
    if (!buffer || !out || requiredBytes <= 0) return false;
    void *ptr = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!ptr || capacity < requiredBytes) return false;
    *out = static_cast<ARUint8 *>(ptr);
    return true;
}

} // namespace
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
    if (width <= 0 || height <= 0) return JNI_FALSE;
    KpmHandle *h = kpmCreateHandleHomography(static_cast<int>(width), static_cast<int>(height));
    if (!h) {
        LOGE("nativeKpmSmokeTest: kpmCreateHandleHomography returned null");
        return JNI_FALSE;
    }
    kpmDeleteHandle(&h);
    LOGI("nativeKpmSmokeTest: KPM handle created and destroyed OK (%dx%d)", width, height);
    return JNI_TRUE;
#else
    (void) width;
    (void) height;
    LOGI("nativeKpmSmokeTest: built without HAVE_ARX_KPM (submodule absent)");
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_KpmBridge_nativeCreateHomographySession(
        JNIEnv *, jobject, jint width, jint height) {
#ifdef HAVE_ARX_KPM
    if (width <= 0 || height <= 0) return 0;

    auto *session = new KpmSession();
    session->handle = kpmCreateHandleHomography(static_cast<int>(width), static_cast<int>(height));
    if (!session->handle) {
        delete session;
        LOGE("nativeCreateHomographySession: KPM handle creation failed");
        return 0;
    }
    session->frameWidth = static_cast<int>(width);
    session->frameHeight = static_cast<int>(height);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
#else
    (void) width;
    (void) height;
    return 0;
#endif
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_KpmBridge_nativeAddPlanarPage(
        JNIEnv *env,
        jobject,
        jlong sessionValue,
        jobject lumaBuffer,
        jint width,
        jint height,
        jfloat referenceDpi,
        jint pageNo,
        jint imageNo,
        jint maxFeatures) {
#ifdef HAVE_ARX_KPM
    KpmSession *session = asSession(sessionValue);
    if (!session || !session->handle) return -1;
    if (width <= 0 || height <= 0 || referenceDpi <= 0.0f ||
        pageNo < 0 || imageNo < 0 || maxFeatures <= 0) {
        return -2;
    }

    const jlong requiredBytes = static_cast<jlong>(width) * static_cast<jlong>(height);
    ARUint8 *luma = nullptr;
    if (!directBuffer(env, lumaBuffer, requiredBytes, &luma)) return -3;

    std::lock_guard<std::mutex> lock(session->mutex);
    const int before = session->atlas ? session->atlas->num : 0;
    const int addResult = kpmAddRefDataSet(
        luma,
        static_cast<int>(width),
        static_cast<int>(height),
        referenceDpi,
        KpmProcFullSize,
        KpmCompNull,
        static_cast<int>(maxFeatures),
        static_cast<int>(pageNo),
        static_cast<int>(imageNo),
        &session->atlas
    );
    if (addResult < 0 || !session->atlas) {
        LOGE("nativeAddPlanarPage: kpmAddRefDataSet failed (%d)", addResult);
        return -4;
    }

    const int generated = session->atlas->num - before;
    if (generated <= 0) {
        LOGE("nativeAddPlanarPage: no features generated");
        return -5;
    }

    const int setResult = kpmSetRefDataSet(session->handle, session->atlas);
    if (setResult < 0) {
        LOGE("nativeAddPlanarPage: kpmSetRefDataSet failed (%d)", setResult);
        return -6;
    }

    LOGI(
        "nativeAddPlanarPage: page=%d image=%d features=%d atlasFeatures=%d",
        pageNo,
        imageNo,
        generated,
        session->atlas->num
    );
    return generated;
#else
    (void) env;
    (void) sessionValue;
    (void) lumaBuffer;
    (void) width;
    (void) height;
    (void) referenceDpi;
    (void) pageNo;
    (void) imageNo;
    (void) maxFeatures;
    return -1;
#endif
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_KpmBridge_nativeMatchPlanar(
        JNIEnv *env,
        jobject,
        jlong sessionValue,
        jobject lumaBuffer,
        jfloatArray outArray) {
#ifdef HAVE_ARX_KPM
    KpmSession *session = asSession(sessionValue);
    if (!session || !session->handle || !session->atlas || !outArray) return -1;
    if (env->GetArrayLength(outArray) < 14) return -1;

    const jlong requiredBytes =
        static_cast<jlong>(session->frameWidth) * static_cast<jlong>(session->frameHeight);
    ARUint8 *luma = nullptr;
    if (!directBuffer(env, lumaBuffer, requiredBytes, &luma)) return -1;

    std::lock_guard<std::mutex> lock(session->mutex);
    if (kpmMatching(session->handle, luma) < 0) return -1;

    float pose[3][4] = {};
    int pageNo = -1;
    float error = 0.0f;
    if (kpmGetPose(session->handle, pose, &pageNo, &error) < 0 || pageNo < 0) return -1;

    int inliers = 0;
    KpmResult *results = nullptr;
    int resultCount = 0;
    if (kpmGetResult(session->handle, &results, &resultCount) == 0 && results) {
        for (int i = 0; i < resultCount; ++i) {
            if (results[i].camPoseF == 0 && results[i].pageNo == pageNo) {
                inliers = results[i].inlierNum;
                break;
            }
        }
    }

    jfloat out[14] = {};
    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 4; ++c) {
            out[r * 4 + c] = pose[r][c];
        }
    }
    out[12] = error;
    out[13] = static_cast<float>(inliers);
    env->SetFloatArrayRegion(outArray, 0, 14, out);
    return static_cast<jint>(pageNo);
#else
    (void) env;
    (void) sessionValue;
    (void) lumaBuffer;
    (void) outArray;
    return -1;
#endif
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_KpmBridge_nativeDestroySession(
        JNIEnv *, jobject, jlong sessionValue) {
#ifdef HAVE_ARX_KPM
    KpmSession *session = asSession(sessionValue);
    if (!session) return;

    if (session->atlas) kpmDeleteRefDataSet(&session->atlas);
    if (session->handle) kpmDeleteHandle(&session->handle);
    delete session;
#else
    (void) sessionValue;
#endif
}

} // extern "C"
