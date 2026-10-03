// JNI bridge to the forked artoolkitX KPM tracker.
//
// ARCore remains an independent first-class pose source. This file exposes only the native KPM
// primitive used by the side-by-side :sphereslam library.
//
// IMPORTANT: artoolkitX 1.1.23's BINARY_FEATURE/FREAK matching path unconditionally runs its
// calibrated pose solve. kpmCreateHandleHomography() therefore leaves a null cparamLT that the
// matcher later dereferences. Runtime sessions here use kpmCreateHandle() with explicit intrinsics;
// the homography constructor is retained only for the link-only smoke test.

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <mutex>
#include <set>

#define LOG_TAG "POSEPROBE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef HAVE_ARX_KPM
#include <ARX/AR/ar.h>
#include <ARX/KPM/kpm.h>

// makeCameraParams() writes the OpenCV "version-5" dist_factor layout (fx,fy,cx,cy at indices [12..15],
// scale at [16]) but builds the ARParam with AR_DIST_FUNCTION_VERSION_DEFAULT. If that default is not
// version 5 the intrinsics land in the wrong slots and every KPM pose is solved with garbage
// calibration — silently. Fail the build loudly instead of shipping miscalibrated tracking.
static_assert(AR_DIST_FUNCTION_VERSION_DEFAULT == 5,
              "KpmBridge assumes the OpenCV v5 dist_factor layout; update makeCameraParams if the "
              "default artoolkitX distortion-function version changes.");

namespace {

struct KpmSession {
    KpmHandle *handle = nullptr;
    KpmRefDataSet *atlas = nullptr;
    ARParamLT *cameraParams = nullptr;
    int frameWidth = 0;
    int frameHeight = 0;
    // Lifetime + mutual exclusion is handled by gKpmRegistryMutex below, not a per-session lock.
};

KpmSession *asSession(jlong value) {
    return reinterpret_cast<KpmSession *>(static_cast<intptr_t>(value));
}

// Lifetime guard. nativeDestroySession can run on a different thread than nativeMatchPlanar /
// nativeAddPlanarPage; the per-session std::mutex cannot protect against the session (and that mutex)
// being deleted out from under an in-flight call. This registry serializes create/destroy against the
// work functions and lets them confirm the session is still alive before dereferencing it, closing the
// use-after-free. (Mirrors gEngineMutex's role for the MobileGS engine.) Holding it across kpmMatching
// serializes KPM globally, which is acceptable: a session is driven by a single frame pipeline.
std::mutex gKpmRegistryMutex;
std::set<KpmSession *> gKpmSessions;

bool directBuffer(JNIEnv *env, jobject buffer, jlong requiredBytes, ARUint8 **out) {
    if (!buffer || !out || requiredBytes <= 0) return false;
    void *ptr = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!ptr || capacity < requiredBytes) return false;
    *out = static_cast<ARUint8 *>(ptr);
    return true;
}

ARParamLT *makeCameraParams(
        int width,
        int height,
        float fx,
        float fy,
        float cx,
        float cy) {
    if (width <= 0 || height <= 0 || fx <= 0.0f || fy <= 0.0f) return nullptr;

    ARParam param{};
    if (arParamClear(&param, width, height, AR_DIST_FUNCTION_VERSION_DEFAULT) < 0) {
        return nullptr;
    }

    // Camera matrix K in artoolkitX's 3x4 form.
    param.mat[0][0] = static_cast<ARdouble>(fx);
    param.mat[0][1] = 0.0;
    param.mat[0][2] = static_cast<ARdouble>(cx);
    param.mat[0][3] = 0.0;
    param.mat[1][0] = 0.0;
    param.mat[1][1] = static_cast<ARdouble>(fy);
    param.mat[1][2] = static_cast<ARdouble>(cy);
    param.mat[1][3] = 0.0;
    param.mat[2][0] = 0.0;
    param.mat[2][1] = 0.0;
    param.mat[2][2] = 1.0;
    param.mat[2][3] = 0.0;

    // Version-5 distortion layout is OpenCV's 12 coefficients followed by fx/fy/cx/cy/scale.
    // Coefficients remain zero from arParamClear(), so this is an identity distortion model in the
    // correct pixel calibration. Camera2 distortion can be plumbed here later without changing KPM.
    param.dist_factor[12] = static_cast<ARdouble>(fx);
    param.dist_factor[13] = static_cast<ARdouble>(fy);
    param.dist_factor[14] = static_cast<ARdouble>(cx);
    param.dist_factor[15] = static_cast<ARdouble>(cy);
    param.dist_factor[16] = 1.0;

    return arParamLTCreate(&param, AR_PARAM_LT_DEFAULT_OFFSET);
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
    LOGI("nativeKpmSmokeTest: KPM symbols linked OK (%dx%d)", width, height);
    return JNI_TRUE;
#else
    (void) width;
    (void) height;
    LOGI("nativeKpmSmokeTest: built without HAVE_ARX_KPM (submodule absent)");
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_KpmBridge_nativeCreateCalibratedSession(
        JNIEnv *,
        jobject,
        jint width,
        jint height,
        jfloat fx,
        jfloat fy,
        jfloat cx,
        jfloat cy) {
#ifdef HAVE_ARX_KPM
    if (width <= 0 || height <= 0 || fx <= 0.0f || fy <= 0.0f) return 0;

    auto *session = new KpmSession();
    session->cameraParams = makeCameraParams(
        static_cast<int>(width),
        static_cast<int>(height),
        fx,
        fy,
        cx,
        cy
    );
    if (!session->cameraParams) {
        delete session;
        LOGE("nativeCreateCalibratedSession: camera parameter creation failed");
        return 0;
    }

    session->handle = kpmCreateHandle(session->cameraParams);
    if (!session->handle) {
        arParamLTFree(&session->cameraParams);
        delete session;
        LOGE("nativeCreateCalibratedSession: KPM handle creation failed");
        return 0;
    }

    session->frameWidth = static_cast<int>(width);
    session->frameHeight = static_cast<int>(height);
    {
        std::lock_guard<std::mutex> registryLock(gKpmRegistryMutex);
        gKpmSessions.insert(session);
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
#else
    (void) width;
    (void) height;
    (void) fx;
    (void) fy;
    (void) cx;
    (void) cy;
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
    // Hold the registry lock across the whole call and confirm the session is still alive before any
    // dereference, so a concurrent nativeDestroySession cannot free it mid-use.
    std::lock_guard<std::mutex> registryLock(gKpmRegistryMutex);
    if (!session || gKpmSessions.find(session) == gKpmSessions.end() || !session->handle) return -1;
    if (width <= 0 || height <= 0 || referenceDpi <= 0.0f ||
        pageNo < 0 || imageNo < 0 || maxFeatures <= 0) {
        return -2;
    }

    const jlong requiredBytes = static_cast<jlong>(width) * static_cast<jlong>(height);
    ARUint8 *luma = nullptr;
    if (!directBuffer(env, lumaBuffer, requiredBytes, &luma)) return -3;

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
    // Hold the registry lock across the whole call and confirm the session is still alive before any
    // dereference, so a concurrent nativeDestroySession cannot free it mid-match.
    std::lock_guard<std::mutex> registryLock(gKpmRegistryMutex);
    if (!session || gKpmSessions.find(session) == gKpmSessions.end() ||
        !session->handle || !session->atlas || !outArray) {
        return -1;
    }
    if (env->GetArrayLength(outArray) < 14) return -1;

    const jlong requiredBytes =
        static_cast<jlong>(session->frameWidth) * static_cast<jlong>(session->frameHeight);
    ARUint8 *luma = nullptr;
    if (!directBuffer(env, lumaBuffer, requiredBytes, &luma)) return -1;

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
    // Take the registry lock and remove the session atomically. erase() returning 0 means it was never
    // registered or already destroyed (double-free guard). Any in-flight add/match holds this same lock,
    // so by the time we proceed no other thread can be inside the session.
    std::lock_guard<std::mutex> registryLock(gKpmRegistryMutex);
    if (!session || gKpmSessions.erase(session) == 0) return;

    if (session->atlas) kpmDeleteRefDataSet(&session->atlas);
    if (session->handle) kpmDeleteHandle(&session->handle);
    if (session->cameraParams) arParamLTFree(&session->cameraParams);
    delete session;
#else
    (void) sessionValue;
#endif
}

} // extern "C"
