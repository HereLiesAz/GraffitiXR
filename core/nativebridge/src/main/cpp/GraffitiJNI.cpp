#include <jni.h>
#include <string>
#include <vector>
#include <android/bitmap.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>
#include "MobileGS.h"

inline MobileGS* getEngine(jlong handle) {
    return reinterpret_cast<MobileGS*>(handle);
}

// Helper: Bitmap -> cv::Mat (Grayscale)
bool bitmapToGrayscaleMat(JNIEnv *env, jobject bitmap, cv::Mat &dst) {
    AndroidBitmapInfo info;
    void *pixels = nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return false;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return false;

    // Create Mat from raw pixels (RGBA)
    cv::Mat src(info.height, info.width, CV_8UC4, pixels);

    // Convert to Grayscale
    cv::cvtColor(src, dst, cv::COLOR_RGBA2GRAY);

    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initNativeJni(JNIEnv *env, jobject thiz) {
    auto *engine = new MobileGS();
    engine->initialize();
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

    if (env->GetArrayLength(viewMtx) < 16 || env->GetArrayLength(projMtx) < 16) return;

    jfloat* view = env->GetFloatArrayElements(viewMtx, nullptr);
    jfloat* proj = env->GetFloatArrayElements(projMtx, nullptr);

    if (view && proj) {
        getEngine(handle)->updateCamera(view, proj);
    }

    if (view) env->ReleaseFloatArrayElements(viewMtx, view, 0);
    if (proj) env->ReleaseFloatArrayElements(projMtx, proj, 0);
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
    jlong capacity = env->GetDirectBufferCapacity(depthBuffer);

    if (depthData && capacity >= 0) {
        if ((long)height * stride > capacity) {
            return;
        }
    }

    float* colorData = nullptr;
    if (colorBuffer != nullptr) {
        colorData = (float*)env->GetDirectBufferAddress(colorBuffer);
    }

    if (env->GetArrayLength(poseMatrix) < 16) return;
    jfloat* pose = env->GetFloatArrayElements(poseMatrix, nullptr);

    if (depthData && pose) {
        getEngine(handle)->feedDepthData(depthData, colorData, width, height, stride, pose, fov);
    }

    if (pose) env->ReleaseFloatArrayElements(poseMatrix, pose, 0);
}

/**
 * IMPLEMENTATION: Set Target Descriptors
 * Converts the Java byte array to a cv::Mat and passes it to MobileGS.
 */
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_setTargetDescriptorsJni(
        JNIEnv *env, jobject thiz,
        jlong handle,
        jbyteArray descriptorBytes,
        jint rows, jint cols, jint type) {

    if (handle == 0) return;

    jbyte* data = env->GetByteArrayElements(descriptorBytes, nullptr);
    if (data) {
        // Create Mat wrapping the data (deep copy happens inside setTargetDescriptors if used properly)
        cv::Mat descriptors(rows, cols, type, (void*)data);
        getEngine(handle)->setTargetDescriptors(descriptors);
        env->ReleaseByteArrayElements(descriptorBytes, data, 0);
    }
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
    getEngine(handle)->onSurfaceChanged(width, height);
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_saveWorld(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle == 0) return false;
    const char *nativePath = env->GetStringUTFChars(path, 0);
    if (!nativePath) return false;
    bool result = getEngine(handle)->saveModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_loadWorld(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle == 0) return false;
    const char *nativePath = env->GetStringUTFChars(path, 0);
    if (!nativePath) return false;
    bool result = getEngine(handle)->loadModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return result;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_alignMapJni(JNIEnv *env, jobject thiz, jlong handle, jfloatArray transformMtx) {
    if (handle == 0) return;
    if (env->GetArrayLength(transformMtx) < 16) return;
    jfloat* transform = env->GetFloatArrayElements(transformMtx, nullptr);
    if (transform) {
        getEngine(handle)->alignMap(transform);
        env->ReleaseFloatArrayElements(transformMtx, transform, 0);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_clearMapJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    getEngine(handle)->clear();
}

// --- Stateless Utilities (GraffitiJNI.kt Object) ---

JNIEXPORT jbyteArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GraffitiJNI_extractFeaturesFromBitmap(JNIEnv *env, jobject thiz, jobject bitmap) {
    cv::Mat gray;
    if (!bitmapToGrayscaleMat(env, bitmap, gray)) return nullptr;

    auto orb = cv::ORB::create();
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    orb->detectAndCompute(gray, cv::noArray(), keypoints, descriptors);

    if (descriptors.empty()) return nullptr;

    // Serialize to byte array
    int size = descriptors.total() * descriptors.elemSize();
    jbyteArray result = env->NewByteArray(size);
    env->SetByteArrayRegion(result, 0, size, (jbyte*)descriptors.data);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GraffitiJNI_extractFeaturesMeta(JNIEnv *env, jobject thiz, jobject bitmap) {
    cv::Mat gray;
    if (!bitmapToGrayscaleMat(env, bitmap, gray)) return nullptr;

    auto orb = cv::ORB::create();
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    orb->detectAndCompute(gray, cv::noArray(), keypoints, descriptors);

    if (descriptors.empty()) return nullptr;

    jintArray result = env->NewIntArray(3);
    jint meta[3];
    meta[0] = descriptors.rows;
    meta[1] = descriptors.cols;
    meta[2] = descriptors.type();
    env->SetIntArrayRegion(result, 0, 3, meta);
    return result;
}

// Stubs for legacy methods referenced in GraffitiJNI.kt
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GraffitiJNI_init(JNIEnv *env, jobject thiz, jint width, jint height) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GraffitiJNI_cleanup(JNIEnv *env, jobject thiz) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GraffitiJNI_update(JNIEnv *env, jobject thiz, jlong matAddr, jfloatArray viewMatrix, jfloatArray projMatrix) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GraffitiJNI_setTargetDescriptors(JNIEnv *env, jobject thiz, jbyteArray descriptorBytes, jint rows, jint cols, jint type) {}

} // extern "C"