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

// Global Engine Instance
static std::unique_ptr<MobileGS> gEngine;

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_core_native_GraffitiJNI_init(JNIEnv *env, jobject thiz, jint width, jint height) {
    if (!gEngine) {
        gEngine = std::make_unique<MobileGS>();
    }
    gEngine->Initialize(width, height);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_core_native_GraffitiJNI_cleanup(JNIEnv *env, jobject thiz) {
    if (gEngine) {
        gEngine->Cleanup();
        gEngine.reset();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_core_native_GraffitiJNI_update(JNIEnv *env, jobject thiz, jlong matAddr, jfloatArray viewMatrix, jfloatArray projMatrix) {
    if (!gEngine) return;

    cv::Mat* pMat = (cv::Mat*)matAddr;
    jfloat* view = env->GetFloatArrayElements(viewMatrix, NULL);
    jfloat* proj = env->GetFloatArrayElements(projMatrix, NULL);

    gEngine->Update(*pMat, view, proj);

    env->ReleaseFloatArrayElements(viewMatrix, view, 0);
    env->ReleaseFloatArrayElements(projMatrix, proj, 0);
}

// --- Teleological Functions ---

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_core_native_GraffitiJNI_setTargetDescriptors(JNIEnv *env, jobject thiz, jbyteArray descriptorBytes, jint rows, jint cols, jint type) {
    if (!gEngine) return;

    jbyte* data = env->GetByteArrayElements(descriptorBytes, NULL);

    // Create Mat from raw bytes
    cv::Mat descriptors(rows, cols, type);
    memcpy(descriptors.data, data, rows * cols * descriptors.elemSize());

    gEngine->SetTargetDescriptors(descriptors);

    env->ReleaseByteArrayElements(descriptorBytes, data, 0);
}

JNIEXPORT jbyteArray JNICALL
Java_com_hereliesaz_graffitixr_core_native_GraffitiJNI_extractFeaturesFromBitmap(JNIEnv *env, jobject thiz, jobject bitmap) {
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
Java_com_hereliesaz_graffitixr_core_native_GraffitiJNI_extractFeaturesMeta(JNIEnv *env, jobject thiz, jobject bitmap) {
    // Helper to return [rows, cols, type] for the extracted features
    // Must be called on the same bitmap state to ensure consistency,
    // ideally passing the mat directly, but for JNI simplicity we re-process or assume consistency.
    // For production optimization: do both extraction and meta in one call returning a struct/object.

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

}