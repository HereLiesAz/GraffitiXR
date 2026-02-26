#include <jni.h>
#include <string>
#include "MobileGS.h"
#include "StereoProcessor.h"
#include "VulkanBackend.h"
#include <android/log.h>
#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>
#include <opencv2/imgproc.hpp>
#include <opencv2/photo.hpp>
#include <cmath>

#define TAG "GraffitiJNI"
#if defined(NDEBUG)
#define LOGE(...)
#else
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#endif

// Helper function to lock Android Bitmap into OpenCV Mat
void bitmapToMat(JNIEnv *env, jobject bitmap, cv::Mat &dst) {
    AndroidBitmapInfo info;
    void *pixels;
    AndroidBitmap_getInfo(env, bitmap, &info);
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    cv::Mat tmp(info.height, info.width, CV_8UC4, pixels);
    tmp.copyTo(dst);
    AndroidBitmap_unlockPixels(env, bitmap);
}

// Helper function to map OpenCV Mat back to Android Bitmap
void matToBitmap(JNIEnv *env, const cv::Mat &src, jobject bitmap) {
    AndroidBitmapInfo info;
    void *pixels;
    AndroidBitmap_getInfo(env, bitmap, &info);
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    cv::Mat tmp(info.height, info.width, CV_8UC4, pixels);
    if (src.channels() == 4) src.copyTo(tmp);
    else if (src.channels() == 3) cv::cvtColor(src, tmp, cv::COLOR_RGB2RGBA);
    else if (src.channels() == 1) cv::cvtColor(src, tmp, cv::COLOR_GRAY2RGBA);
    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" {

static StereoProcessor* g_stereoProcessor = nullptr;

JNIEXPORT jlong JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_createNativeInstance(JNIEnv *env, jobject thiz) {
    auto *engine = new MobileGS();
    if (!g_stereoProcessor) g_stereoProcessor = new StereoProcessor();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroyJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) delete reinterpret_cast<MobileGS *>(handle);
    if (g_stereoProcessor) { delete g_stereoProcessor; g_stereoProcessor = nullptr; }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initializeJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) reinterpret_cast<MobileGS *>(handle)->initialize();
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_resetGLStateJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) reinterpret_cast<MobileGS *>(handle)->reset();
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_onSurfaceChangedJni(JNIEnv *env, jobject thiz, jlong handle, jint width, jint height) {
    if (handle != 0) reinterpret_cast<MobileGS *>(handle)->onSurfaceChanged(width, height);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_drawJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) reinterpret_cast<MobileGS *>(handle)->draw();
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateCameraJni(JNIEnv *env, jobject thiz, jlong handle, jfloatArray view, jfloatArray proj) {
    if (handle != 0) {
        auto *engine = reinterpret_cast<MobileGS *>(handle);
        jfloat *v = env->GetFloatArrayElements(view, nullptr);
        jfloat *p = env->GetFloatArrayElements(proj, nullptr);
        engine->updateCamera(v, p);
        env->ReleaseFloatArrayElements(view, v, 0);
        env->ReleaseFloatArrayElements(proj, p, 0);
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateLightJni(JNIEnv *env, jobject thiz, jlong handle, jfloat intensity, jfloatArray color) {
    if (handle != 0) {
        jfloat *c = color ? env->GetFloatArrayElements(color, nullptr) : nullptr;
        reinterpret_cast<MobileGS *>(handle)->updateLight(intensity, c);
        if (c) env->ReleaseFloatArrayElements(color, c, 0);
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedDepthDataJni(JNIEnv *env, jobject thiz, jlong handle, jobject buffer, jint width, jint height) {
    if (handle != 0 && buffer) {
        uint8_t* data = (uint8_t*)env->GetDirectBufferAddress(buffer);
        if (data) reinterpret_cast<MobileGS *>(handle)->processDepthData(data, width, height);
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedMonocularDataJni(JNIEnv *env, jobject thiz, jlong handle, jobject buffer, jint width, jint height) {
    if (handle != 0 && buffer) {
        uint8_t* data = (uint8_t*)env->GetDirectBufferAddress(buffer);
        if (data) reinterpret_cast<MobileGS *>(handle)->processMonocularData(data, width, height);
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedStereoDataJni(
        JNIEnv *env, jobject thiz, jlong handle, jobject leftBuffer, jint leftWidth, jint leftHeight, jint leftStride, jobject rightBuffer, jint rightWidth, jint rightHeight, jint rightStride) {
    if (g_stereoProcessor && handle != 0) {
        uint8_t* leftPtr = (uint8_t*)env->GetDirectBufferAddress(leftBuffer);
        uint8_t* rightPtr = (uint8_t*)env->GetDirectBufferAddress(rightBuffer);
        if (leftPtr && rightPtr) g_stereoProcessor->process(reinterpret_cast<MobileGS *>(handle), leftPtr, leftWidth, leftHeight, leftStride, rightPtr, rightWidth, rightHeight, rightStride);
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedLocationDataJni(JNIEnv *env, jobject thiz, jlong handle, jdouble latitude, jdouble longitude, jdouble altitude) {
    if (handle != 0) {
        reinterpret_cast<MobileGS *>(handle)->setLocation(latitude, longitude, altitude);
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_alignMapJni(JNIEnv *env, jobject thiz, jlong handle, jfloatArray transform) {
    if (handle != 0) {
        jfloat *t = env->GetFloatArrayElements(transform, nullptr);
        if (t) reinterpret_cast<MobileGS *>(handle)->alignMap(t);
        env->ReleaseFloatArrayElements(transform, t, 0);
    }
}

JNIEXPORT jboolean JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_saveKeyframeJni(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle != 0) {
        const char *nativePath = env->GetStringUTFChars(path, 0);
        bool result = reinterpret_cast<MobileGS *>(handle)->saveKeyframe(nativePath);
        env->ReleaseStringUTFChars(path, nativePath);
        return result;
    }
    return false;
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_setVisualizationModeJni(JNIEnv *env, jobject thiz, jlong handle, jint mode) {
    if (handle != 0) reinterpret_cast<MobileGS *>(handle)->setVisualizationMode(mode);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initVulkanJni(JNIEnv *env, jobject thiz, jlong handle, jobject surface, jobject assetManager) {
    if (handle != 0 && surface) reinterpret_cast<MobileGS *>(handle)->initVulkan(ANativeWindow_fromSurface(env, surface), AAssetManager_fromJava(env, assetManager));
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_resizeVulkanJni(JNIEnv *env, jobject thiz, jlong handle, jint width, jint height) {
    if (handle != 0) reinterpret_cast<MobileGS *>(handle)->resizeVulkan(width, height);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroyVulkanJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) reinterpret_cast<MobileGS *>(handle)->destroyVulkan();
}

JNIEXPORT jboolean JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_saveWorldJni(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle != 0) {
        const char *nativePath = env->GetStringUTFChars(path, 0);
        bool result = reinterpret_cast<MobileGS *>(handle)->saveMap(nativePath);
        env->ReleaseStringUTFChars(path, nativePath);
        return result;
    }
    return false;
}

JNIEXPORT jboolean JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_loadWorldJni(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle != 0) {
        const char *nativePath = env->GetStringUTFChars(path, 0);
        bool result = reinterpret_cast<MobileGS *>(handle)->loadMap(nativePath);
        env->ReleaseStringUTFChars(path, nativePath);
        return result;
    }
    return false;
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateMeshJni(JNIEnv *env, jobject thiz, jlong handle, jfloatArray vertices) { }

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_processHealJni(JNIEnv *env, jobject thiz, jlong handle, jobject bitmap, jobject mask) {
    if(handle == 0 || !bitmap || !mask) return;
    cv::Mat src, maskMat, dst;
    bitmapToMat(env, bitmap, src);
    bitmapToMat(env, mask, maskMat);

    if(src.channels() == 4) cv::cvtColor(src, src, cv::COLOR_RGBA2RGB);
    if(maskMat.channels() > 1) cv::cvtColor(maskMat, maskMat, cv::COLOR_RGBA2GRAY);

    cv::inpaint(src, maskMat, dst, 3.0, cv::INPAINT_TELEA);
    matToBitmap(env, dst, bitmap);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_processLiquifyJni(JNIEnv *env, jobject thiz, jlong handle, jobject bitmap, jfloatArray meshData) {
    if(handle == 0 || !bitmap || !meshData) return;

    cv::Mat src;
    bitmapToMat(env, bitmap, src);

    jfloat* mesh = env->GetFloatArrayElements(meshData, nullptr);
    jsize len = env->GetArrayLength(meshData);

    // Reverse engineer grid size from flattened array [x,y, x,y...]
    // Size = (rows+1) * (cols+1) * 2
    // Assuming square grid for simplicity in JNI (though WarpableImage handles rectangular)
    // cols = sqrt(len/2) - 1
    int gridSize = (int)(sqrt(len / 2.0)) - 1;

    if (gridSize <= 0) {
        env->ReleaseFloatArrayElements(meshData, mesh, 0);
        return;
    }

    // Create Remap Maps
    cv::Mat mapX(src.size(), CV_32F);
    cv::Mat mapY(src.size(), CV_32F);

    int width = src.cols;
    int height = src.rows;

    float cellW = (float)width / gridSize;
    float cellH = (float)height / gridSize;

    // Bilinear Interpolation of the Mesh Control Points to create pixel-wise map
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            // Find cell index
            int cellX = (int)(x / cellW);
            int cellY = (int)(y / cellH);
            if (cellX >= gridSize) cellX = gridSize - 1;
            if (cellY >= gridSize) cellY = gridSize - 1;

            // Normalized coords within cell
            float u = (x - cellX * cellW) / cellW;
            float v = (y - cellY * cellH) / cellH;

            // Mesh Indices
            int idxTL = (cellY * (gridSize + 1) + cellX) * 2;
            int idxTR = (cellY * (gridSize + 1) + (cellX + 1)) * 2;
            int idxBL = ((cellY + 1) * (gridSize + 1) + cellX) * 2;
            int idxBR = ((cellY + 1) * (gridSize + 1) + (cellX + 1)) * 2;

            // Interpolate X
            float xTop = (1 - u) * mesh[idxTL] + u * mesh[idxTR];
            float xBot = (1 - u) * mesh[idxBL] + u * mesh[idxBR];
            mapX.at<float>(y, x) = (1 - v) * xTop + v * xBot;

            // Interpolate Y
            float yTop = (1 - u) * mesh[idxTL+1] + u * mesh[idxTR+1];
            float yBot = (1 - u) * mesh[idxBL+1] + u * mesh[idxBR+1];
            mapY.at<float>(y, x) = (1 - v) * yTop + v * yBot;
        }
    }

    cv::Mat dst;
    cv::remap(src, dst, mapX, mapY, cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0,0,0,0));

    env->ReleaseFloatArrayElements(meshData, mesh, 0);
    matToBitmap(env, dst, bitmap);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_processBurnDodgeJni(JNIEnv *env, jobject thiz, jlong handle, jobject bitmap, jobject mask, jboolean isBurn) {
    if(handle == 0 || !bitmap || !mask) return;

    AndroidBitmapInfo info;
    void *pixels;
    void *maskPixels;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return;

    AndroidBitmapInfo maskInfo;
    if (AndroidBitmap_getInfo(env, mask, &maskInfo) < 0) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return;
    }
    if (AndroidBitmap_lockPixels(env, mask, &maskPixels) < 0) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return;
    }

    uint32_t* src = (uint32_t*)pixels;
    uint32_t* msk = (uint32_t*)maskPixels;
    int count = info.width * info.height;

    // Multiplier: Burn < 1.0, Dodge > 1.0
    float factor = isBurn ? 0.9f : 1.1f;

    for(int i = 0; i < count; ++i) {
        // Read Mask Alpha or Red channel
        uint32_t maskVal = msk[i];
        int alpha = (maskVal >> 24) & 0xFF;

        if (alpha > 10) { // If mask is present
            uint32_t p = src[i];
            int r = (p) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = (p >> 16) & 0xFF;
            int a = (p >> 24) & 0xFF;

            // Apply simple exposure adjustment
            r = std::min(255, std::max(0, (int)(r * factor)));
            g = std::min(255, std::max(0, (int)(g * factor)));
            b = std::min(255, std::max(0, (int)(b * factor)));

            src[i] = (a << 24) | (b << 16) | (g << 8) | r;
        }
    }

    AndroidBitmap_unlockPixels(env, mask);
    AndroidBitmap_unlockPixels(env, bitmap);
}

} // extern "C"