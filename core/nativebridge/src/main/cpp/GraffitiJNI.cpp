// ~~~ FILE: ./core/nativebridge/src/main/cpp/GraffitiJNI.cpp ~~~
#include <jni.h>
#include <vector>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include "include/VulkanBackend.h"
#include "include/MobileGS.h"
#include "include/StereoProcessor.h"

#define LOG_TAG "GraffitiJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global Engine Singletons
static VulkanBackend* gVulkanRenderer = nullptr;
static MobileGS* gSlamEngine = nullptr;
static StereoProcessor gStereoProcessor;

// Overlay bitmap stored as RGBA cv::Mat for Vulkan texture upload
static cv::Mat gOverlayBitmap;

// Last known GPS fix for geo-anchoring
struct Gpsfix { double lat = 0, lon = 0, alt = 0; bool valid = false; };
static Gpsfix gLastGps;

extern "C" {

// --- Lifecycle Management ---

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeInitialize(JNIEnv* env, jobject thiz) {
    if (!gSlamEngine) gSlamEngine = new MobileGS();
    gSlamEngine->initialize(1920, 1080); // Default resolution, updated by onSurfaceChanged
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeEnsureInitialized(JNIEnv* env, jobject thiz) {
    if (!gSlamEngine) {
        gSlamEngine = new MobileGS();
        gSlamEngine->initialize(1920, 1080);
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeDestroy(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) {
        delete gSlamEngine;
        gSlamEngine = nullptr;
    }
    if (gVulkanRenderer) {
        gVulkanRenderer->destroy();
        delete gVulkanRenderer;
        gVulkanRenderer = nullptr;
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeCreateOnGlThread(JNIEnv* env, jobject thiz) {
    // Vulkan manages its own thread context, but we keep this stub for legacy GL hooks if needed
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeResetGLState(JNIEnv* env, jobject thiz) {
    // No-op for Vulkan
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetVisualizationMode(JNIEnv* env, jobject thiz, jint mode) {
    if (gVulkanRenderer) gVulkanRenderer->setVisualizationMode(mode);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeOnSurfaceChanged(JNIEnv* env, jobject thiz, jint width, jint height) {
    if (gSlamEngine) gSlamEngine->initialize(width, height);
    if (gVulkanRenderer) gVulkanRenderer->resize(width, height);
}

// --- Rendering Loop ---

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeDraw(JNIEnv* env, jobject thiz) {
    if (gVulkanRenderer && gSlamEngine) {
        // Thread-safe data transfer: Engine (Physics) -> Renderer (Vulkan)
        std::lock_guard<std::mutex> lock(gSlamEngine->getMutex());
        gVulkanRenderer->renderFrame(gSlamEngine->getSplats());
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetBitmap(JNIEnv* env, jobject thiz, jobject bitmap) {
    if (!bitmap) {
        gOverlayBitmap.release();
        return;
    }
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("nativeSetBitmap: AndroidBitmap_getInfo failed");
        return;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("nativeSetBitmap: AndroidBitmap_lockPixels failed");
        return;
    }
    // Copy ARGB_8888 pixel data into an OpenCV Mat (BGRA layout from Android → convert to RGBA)
    cv::Mat src(info.height, info.width, CV_8UC4, pixels);
    cv::Mat rgba;
    cv::cvtColor(src, rgba, cv::COLOR_BGRA2RGBA);
    gOverlayBitmap = rgba.clone(); // Deep copy before unlocking
    AndroidBitmap_unlockPixels(env, bitmap);
    if (gVulkanRenderer) {
        gVulkanRenderer->setOverlayTexture(rgba.cols, rgba.rows, gOverlayBitmap.data);
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUpdateCamera(JNIEnv* env, jobject thiz, jfloatArray viewMatrix, jfloatArray projMatrix) {
    if (gSlamEngine) {
        jfloat* view = env->GetFloatArrayElements(viewMatrix, nullptr);
        jfloat* proj = env->GetFloatArrayElements(projMatrix, nullptr);

        gSlamEngine->updateCamera(view, proj);
        if (gVulkanRenderer) {
            gVulkanRenderer->updateCamera(view, proj);
        }

        env->ReleaseFloatArrayElements(viewMatrix, view, JNI_ABORT);
        env->ReleaseFloatArrayElements(projMatrix, proj, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUpdateAnchorTransform(JNIEnv* env, jobject thiz, jfloatArray transform) {
    if (gSlamEngine) {
        jfloat* mat = env->GetFloatArrayElements(transform, nullptr);
        gSlamEngine->updateAnchorTransform(mat);
        env->ReleaseFloatArrayElements(transform, mat, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUpdateLight(JNIEnv* env, jobject thiz, jfloat intensity) {
    if (gVulkanRenderer) {
        float white[] = {1.0f, 1.0f, 1.0f};
        gVulkanRenderer->setLighting(intensity, white);
    }
}

// --- Sensor Data Feeds ---

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedMonocularData(
        JNIEnv* env, jobject thiz, jobject data, jint width, jint height) {
    if (!gSlamEngine || data == nullptr) return;

    // Direct buffer access for high performance (No copy)
    uint8_t* buffer = (uint8_t*)env->GetDirectBufferAddress(data);
    if (!buffer) return;

    // Wrap buffer in OpenCV Mat (assuming Gray/YUV_Y or RGB depending on source)
    // For monocular feeds from ARCore/CameraX, this is typically the Y plane (CV_8UC1)
    cv::Mat frame(height, width, CV_8UC1, buffer);

    // Create a dummy depth map for now since this is monocular
    // In production, this calls a Monocular Depth Estimator (MiDaS/LiteRT)
    cv::Mat dummyDepth = cv::Mat::ones(height, width, CV_32F) * 2.0f; // 2 meters default

    // Convert grayscale to RGB for the engine
    cv::Mat colorFrame;
    cv::cvtColor(frame, colorFrame, cv::COLOR_GRAY2RGB);

    gSlamEngine->processDepthFrame(dummyDepth, colorFrame);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedStereoData(
        JNIEnv* env, jobject thiz, jbyteArray left, jbyteArray right, jint w, jint h) {
    if (!gSlamEngine) return;

    jbyte* l_ptr = env->GetByteArrayElements(left, nullptr);
    jbyte* r_ptr = env->GetByteArrayElements(right, nullptr);
    if (!l_ptr || !r_ptr) {
        if (l_ptr) env->ReleaseByteArrayElements(left, l_ptr, JNI_ABORT);
        if (r_ptr) env->ReleaseByteArrayElements(right, r_ptr, JNI_ABORT);
        return;
    }

    // Copy left frame before releasing (grayscale → RGB color context)
    cv::Mat leftGray(h, w, CV_8UC1, reinterpret_cast<uint8_t*>(l_ptr));
    cv::Mat colorFrame;
    cv::cvtColor(leftGray, colorFrame, cv::COLOR_GRAY2RGB);

    gStereoProcessor.processStereo(
            reinterpret_cast<int8_t*>(l_ptr),
            reinterpret_cast<int8_t*>(r_ptr),
            w, h
    );

    env->ReleaseByteArrayElements(left, l_ptr, JNI_ABORT);
    env->ReleaseByteArrayElements(right, r_ptr, JNI_ABORT);

    cv::Mat disparity = gStereoProcessor.getDisparityMap();
    if (disparity.empty()) return;

    // Convert CV_16S disparity (fixed-point 1/16 pixel) to CV_32F depth in metres.
    // depth ≈ (baseline_mm * focal_px) / disp_px. Use kScale as conservative approximation.
    constexpr float kScale = 500.0f;
    constexpr float kEpsilon = 1e-3f;
    cv::Mat dispFloat;
    disparity.convertTo(dispFloat, CV_32F, 1.0f / 16.0f);
    cv::Mat depthMap(dispFloat.rows, dispFloat.cols, CV_32F);
    for (int r = 0; r < dispFloat.rows; ++r) {
        for (int c = 0; c < dispFloat.cols; ++c) {
            float d = dispFloat.at<float>(r, c);
            depthMap.at<float>(r, c) = (d > kEpsilon) ? (kScale / d) : 0.0f;
        }
    }

    gSlamEngine->processDepthFrame(depthMap, colorFrame);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedLocationData(JNIEnv* env, jobject thiz, jdouble lat, jdouble lon, jdouble alt) {
    gLastGps = {lat, lon, alt, true};
}

JNIEXPORT jdoubleArray JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetLastGps(JNIEnv* env, jobject thiz) {
    jdoubleArray result = env->NewDoubleArray(4);
    if (!result) return nullptr;
    jdouble buf[4] = {gLastGps.lat, gLastGps.lon, gLastGps.alt, gLastGps.valid ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, buf);
    return result;
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeProcessTeleologicalFrame(
        JNIEnv* env, jobject thiz, jobject buffer, jlong timestamp) {
    // Feature extraction hook for map alignment
    uint8_t* data = (uint8_t*)env->GetDirectBufferAddress(buffer);
    if (!data) return;

    // Placeholder: In a full impl, this passes to ORB_SLAM or custom tracker
}

JNIEXPORT jboolean JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSaveKeyframe(JNIEnv* env, jobject thiz, jlong timestamp, jstring outputPath) {
    if (!gSlamEngine) return JNI_FALSE;
    const char* pathCStr = env->GetStringUTFChars(outputPath, nullptr);
    bool result = gSlamEngine->saveModel(std::string(pathCStr));
    env->ReleaseStringUTFChars(outputPath, pathCStr);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSaveModel(JNIEnv* env, jobject thiz, jstring path) {
    if (!gSlamEngine) return JNI_FALSE;
    const char* pathCStr = env->GetStringUTFChars(path, nullptr);
    bool result = gSlamEngine->saveModel(std::string(pathCStr));
    env->ReleaseStringUTFChars(path, pathCStr);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeLoadModel(JNIEnv* env, jobject thiz, jstring path) {
    if (!gSlamEngine) return JNI_FALSE;
    const char* pathCStr = env->GetStringUTFChars(path, nullptr);
    bool result = gSlamEngine->loadModel(std::string(pathCStr));
    env->ReleaseStringUTFChars(path, pathCStr);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeToggleFlashlight(JNIEnv* env, jobject thiz, jboolean enabled) {
    // Hardware control via NDK Camera2 is complex; usually handled in Kotlin.
    // This hook allows the engine to adjust exposure settings if it controlled the camera.
}

// --- Vulkan Lifecycle ---

JNIEXPORT jboolean JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeInitVulkan(
        JNIEnv* env, jobject thiz, jobject surface, jobject asset_mgr, jint width, jint height) {
    if (!gVulkanRenderer) gVulkanRenderer = new VulkanBackend();

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    AAssetManager* mgr = AAssetManager_fromJava(env, asset_mgr);

    if (!gVulkanRenderer->initialize(window, mgr)) {
        delete gVulkanRenderer;
        gVulkanRenderer = nullptr;
        return JNI_FALSE;
    }
    gVulkanRenderer->resize(width, height);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeResizeVulkan(JNIEnv* env, jobject thiz, jint width, jint height) {
    if (gVulkanRenderer) gVulkanRenderer->resize(width, height);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeDestroyVulkan(JNIEnv* env, jobject thiz) {
    if (gVulkanRenderer) {
        gVulkanRenderer->destroy();
        delete gVulkanRenderer;
        gVulkanRenderer = nullptr;
    }
}

// --- Visual Adjustments ---

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_design_rendering_ProjectedImageRenderer_applyNativeColorAdjustment(
        JNIEnv* env, jobject thiz, jfloat brightness, jfloat contrast, jfloat saturation, jfloat r, jfloat g, jfloat b) {
    if (gVulkanRenderer) {
        // Map brightness/contrast/saturation/colorBalance to light intensity/color
        // This assumes VulkanBackend::setLighting handles these parameters.
        // For now, we map 'brightness' to 'intensity' and the others to color correction.
        // In a real implementation, VulkanBackend should have specific methods for these.

        // Simple mapping:
        float intensity = 1.0f + brightness;
        float color[] = {r, g, b};
        gVulkanRenderer->setLighting(intensity, color);
    }
}

} // extern "C"
