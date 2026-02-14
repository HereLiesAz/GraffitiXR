#include <jni.h>
#include <string>
#include <vector>
#include <android/bitmap.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>
#include "MobileGS.h"

// --- Helper Functions ---
inline MobileGS* getEngine(jlong handle) {
    return reinterpret_cast<MobileGS*>(handle);
}

// Bitmap -> cv::Mat (RGBA or Gray)
bool bitmapToMat(JNIEnv *env, jobject bitmap, cv::Mat &dst, bool needGray) {
    AndroidBitmapInfo info;
    void *pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return false;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return false;

    // Create Mat from raw pixels (RGBA)
    cv::Mat src(info.height, info.width, CV_8UC4, pixels);
    if (needGray) {
        cv::cvtColor(src, dst, cv::COLOR_RGBA2GRAY);
    } else {
        src.copyTo(dst);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

// cv::Mat -> Bitmap (Writes into existing bitmap)
bool matToBitmap(JNIEnv *env, const cv::Mat &src, jobject bitmap) {
    AndroidBitmapInfo info;
    void *pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return false;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return false;

    cv::Mat dst(info.height, info.width, CV_8UC4, pixels);
    if (src.type() == CV_8UC1) {
        cv::cvtColor(src, dst, cv::COLOR_GRAY2RGBA);
    } else if (src.type() == CV_8UC3) {
        cv::cvtColor(src, dst, cv::COLOR_BGR2RGBA);
    } else if (src.type() == CV_8UC4) {
        src.copyTo(dst);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

extern "C" {

// --- Engine Lifecycle ---

JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initNativeJni(JNIEnv *env, jobject thiz) {
    auto *engine = new MobileGS();
    engine->initialize();
    return reinterpret_cast<jlong>(engine);
}

// NEW: Reset GL State
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_resetGLJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) {
        getEngine(handle)->resetGL();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroyNativeJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) {
        delete getEngine(handle);
    }
}

// --- Camera & Rendering ---

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_onSurfaceChangedJni(JNIEnv *env, jobject thiz, jlong handle, jint width, jint height) {
    if (handle == 0) return;
    getEngine(handle)->onSurfaceChanged(width, height);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateCameraJni(JNIEnv *env, jobject thiz, jlong handle, jfloatArray viewMtx, jfloatArray projMtx) {
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
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_drawJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    getEngine(handle)->draw();
}

// --- Data Ingestion ---

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedDepthDataJni(JNIEnv *env, jobject thiz, jlong handle, jobject depthBuffer, jobject colorBuffer, jint width, jint height, jint depthStride, jint colorStride, jfloatArray poseMatrix, jfloat fov) {
    if (handle == 0) return;

    // SAFETY: Use GetDirectBufferAddress. Caller must ensure DirectByteBuffer.
    auto* depthData = (uint16_t*)env->GetDirectBufferAddress(depthBuffer);
    if (!depthData) return; // FIX: Added nullptr check

    uint8_t* colorData = nullptr;
    if (colorBuffer != nullptr) {
        colorData = (uint8_t*)env->GetDirectBufferAddress(colorBuffer);
    }

    if (env->GetArrayLength(poseMatrix) < 16) return;
    jfloat* pose = env->GetFloatArrayElements(poseMatrix, nullptr);

    if (pose) {
        getEngine(handle)->feedDepthData(depthData, colorData, width, height, depthStride, colorStride, pose, fov);
        env->ReleaseFloatArrayElements(poseMatrix, pose, 0);
    }
}

// --- Map Management ---

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
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_clearMapJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    getEngine(handle)->clear();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_pruneMapJni(JNIEnv *env, jobject thiz, jlong handle, jint ageThreshold) {
    if (handle == 0) return;
    getEngine(handle)->pruneMap(ageThreshold);
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_getPointCountJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return 0;
    return getEngine(handle)->getSplatCount();
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
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_setTargetDescriptorsJni(JNIEnv *env, jobject thiz, jlong handle, jbyteArray descriptorBytes, jint rows, jint cols, jint type) {
    if (handle == 0) return;
    jbyte* data = env->GetByteArrayElements(descriptorBytes, nullptr);
    if (data) {
        cv::Mat descriptors(rows, cols, type, (void*)data);
        getEngine(handle)->setTargetDescriptors(descriptors);
        env->ReleaseByteArrayElements(descriptorBytes, data, 0);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_trainStepJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) {
        getEngine(handle)->trainStep();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateMeshJni(JNIEnv *env, jobject thiz, jlong handle, jfloatArray vertices) {
    if (handle == 0) return;
    jfloat* verts = env->GetFloatArrayElements(vertices, nullptr);
    jint count = env->GetArrayLength(vertices);
    getEngine(handle)->updateMesh(verts, count / 3);
    env->ReleaseFloatArrayElements(vertices, verts, JNI_ABORT);
}

JNIEXPORT jbyteArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_extractFeaturesFromBitmap(JNIEnv *env, jobject thiz, jobject bitmap) {
    cv::Mat gray;
    if (!bitmapToMat(env, bitmap, gray, true)) return nullptr;

    auto orb = cv::ORB::create();
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    orb->detectAndCompute(gray, cv::noArray(), keypoints, descriptors);

    if (descriptors.empty()) return nullptr;

    int size = descriptors.total() * descriptors.elemSize();
    jbyteArray result = env->NewByteArray(size);
    env->SetByteArrayRegion(result, 0, size, (jbyte*)descriptors.data);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_extractFeaturesMeta(JNIEnv *env, jobject thiz, jobject bitmap) {
    cv::Mat gray;
    if (!bitmapToMat(env, bitmap, gray, true)) return nullptr;

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

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_detectEdgesJni(JNIEnv *env, jobject thiz, jobject srcBitmap, jobject dstBitmap) {
    cv::Mat gray;
    if (!bitmapToMat(env, srcBitmap, gray, true)) return;

    cv::Mat edges;
    cv::Canny(gray, edges, 50, 150);

    // Create a 4-channel BGRA/RGBA matrix
    cv::Mat rgba(edges.rows, edges.cols, CV_8UC4);

    for (int y = 0; y < edges.rows; ++y) {
        for (int x = 0; x < edges.cols; ++x) {
            uchar val = edges.at<uchar>(y, x);
            if (val > 0) {
                // Edge: White and Opaque
                rgba.at<cv::Vec4b>(y, x) = cv::Vec4b(255, 255, 255, 255);
            } else {
                // Background: Transparent
                rgba.at<cv::Vec4b>(y, x) = cv::Vec4b(0, 0, 0, 0);
            }
        }
    }

    matToBitmap(env, rgba, dstBitmap);
}

} // extern "C"
