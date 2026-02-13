#include <jni.h>
#include <string>
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>
#include "MobileGS.h"

#define LOG_TAG "GraffitiJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global Engine Instance (Legacy/GraffitiJNI Object)
static std::unique_ptr<MobileGS> gEngine;

// Helper for SlamManager (Handle-based)
inline MobileGS* getEngine(jlong handle) {
    return reinterpret_cast<MobileGS*>(handle);
}

extern "C" {

// =================================================================================================
// GraffitiJNI (Singleton/Object Interface)
// =================================================================================================

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GraffitiJNI_init(JNIEnv *env, jobject thiz, jint width, jint height) {
    if (!gEngine) {
        gEngine = std::make_unique<MobileGS>();
    }
    gEngine->Initialize(width, height);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GraffitiJNI_cleanup(JNIEnv *env, jobject thiz) {
    if (gEngine) {
        gEngine->Cleanup();
        gEngine.reset();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GraffitiJNI_update(JNIEnv *env, jobject thiz, jlong matAddr, jfloatArray viewMatrix, jfloatArray projMatrix) {
    if (!gEngine) return;

    cv::Mat* pMat = (cv::Mat*)matAddr;
    jfloat* view = env->GetFloatArrayElements(viewMatrix, NULL);
    jfloat* proj = env->GetFloatArrayElements(projMatrix, NULL);

    gEngine->Update(*pMat, view, proj);

    env->ReleaseFloatArrayElements(viewMatrix, view, 0);
    env->ReleaseFloatArrayElements(projMatrix, proj, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GraffitiJNI_setTargetDescriptors(JNIEnv *env, jobject thiz, jbyteArray descriptorBytes, jint rows, jint cols, jint type) {
    if (!gEngine) return;

    jbyte* data = env->GetByteArrayElements(descriptorBytes, NULL);

    // Create Mat from raw bytes
    cv::Mat descriptors(rows, cols, type);
    memcpy(descriptors.data, data, rows * cols * descriptors.elemSize());

    gEngine->SetTargetDescriptors(descriptors);

    env->ReleaseByteArrayElements(descriptorBytes, data, 0);
}

JNIEXPORT jbyteArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GraffitiJNI_extractFeaturesFromBitmap(JNIEnv *env, jobject thiz, jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("Failed to get bitmap info");
        return NULL;
    }

    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        return NULL;
    }

    // Convert Bitmap to Mat (Assume RGBA_8888)
    cv::Mat img(info.height, info.width, CV_8UC4, pixels);
    cv::Mat gray;
    cv::cvtColor(img, gray, cv::COLOR_RGBA2GRAY);

    AndroidBitmap_unlockPixels(env, bitmap);

    // Extract ORB
    cv::Ptr<cv::ORB> orb = cv::ORB::create(1000);
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    orb->detectAndCompute(gray, cv::noArray(), keypoints, descriptors);

    if (descriptors.empty()) return NULL;

    // Serialize to byte array
    size_t dataSize = descriptors.total() * descriptors.elemSize();
    jbyteArray result = env->NewByteArray(dataSize);
    env->SetByteArrayRegion(result, 0, dataSize, (jbyte*)descriptors.data);

    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GraffitiJNI_extractFeaturesMeta(JNIEnv *env, jobject thiz, jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return NULL;

    cv::Mat img(info.height, info.width, CV_8UC4, pixels);
    cv::Mat gray;
    cv::cvtColor(img, gray, cv::COLOR_RGBA2GRAY);
    AndroidBitmap_unlockPixels(env, bitmap);

    cv::Ptr<cv::ORB> orb = cv::ORB::create(1000);
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    orb->detectAndCompute(gray, cv::noArray(), keypoints, descriptors);

    if (descriptors.empty()) return NULL;

    jintArray meta = env->NewIntArray(3);
    jint temp[3];
    temp[0] = descriptors.rows;
    temp[1] = descriptors.cols;
    temp[2] = descriptors.type();
    env->SetIntArrayRegion(meta, 0, 3, temp);

    return meta;
}

// =================================================================================================
// SlamManager (Instance/Handle Interface)
// =================================================================================================

JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initNativeJni(JNIEnv *env, jobject thiz) {
    auto *engine = new MobileGS();
    // Note: Initialize(w, h) is called separately or we should add it here?
    // SlamManager.kt calls onSurfaceChanged which calls Initialize/Resize.
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroyNativeJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) {
        delete getEngine(handle);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateCameraJni(
        JNIEnv *env, jobject thiz, jlong handle, jfloatArray viewMtx, jfloatArray projMtx) {
    if (handle == 0) return;

    jfloat* view = env->GetFloatArrayElements(viewMtx, nullptr);
    jfloat* proj = env->GetFloatArrayElements(projMtx, nullptr);

    getEngine(handle)->updateCamera(view, proj);

    env->ReleaseFloatArrayElements(viewMtx, view, 0);
    env->ReleaseFloatArrayElements(projMtx, proj, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedDepthDataJni(
        JNIEnv *env, jobject thiz,
        jlong handle,
        jobject depthBuffer,
        jobject colorBuffer,
        jint width, jint height,
        jint stride,
        jfloatArray poseMatrix,
        jfloat fov) {

    if (handle == 0) return;

    auto* depthData = (uint16_t*)env->GetDirectBufferAddress(depthBuffer);
    float* colorData = nullptr;
    if (colorBuffer != nullptr) {
        colorData = (float*)env->GetDirectBufferAddress(colorBuffer);
    }

    jfloat* pose = env->GetFloatArrayElements(poseMatrix, nullptr);

    if (depthData && pose) {
        getEngine(handle)->feedDepthData(depthData, colorData, width, height, stride, pose, fov);
    }

    env->ReleaseFloatArrayElements(poseMatrix, pose, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_drawJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    getEngine(handle)->draw();
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_getPointCountJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return 0;
    return getEngine(handle)->getSplatCount();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_onSurfaceChangedJni(JNIEnv *env, jobject thiz, jlong handle, jint width, jint height) {
    if (handle == 0) return;
    getEngine(handle)->Initialize(width, height); // Initialize calls onSurfaceChanged logic basically
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_saveWorld(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle == 0) return false;
    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = getEngine(handle)->saveModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_loadWorld(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle == 0) return false;
    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = getEngine(handle)->loadModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return result;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_alignMapJni(JNIEnv *env, jobject thiz, jlong handle, jfloatArray transformMtx) {
    if (handle == 0) return;
    jfloat* transform = env->GetFloatArrayElements(transformMtx, nullptr);
    getEngine(handle)->alignMap(transform);
    env->ReleaseFloatArrayElements(transformMtx, transform, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_clearMapJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    getEngine(handle)->clear();
}

} // extern "C"
