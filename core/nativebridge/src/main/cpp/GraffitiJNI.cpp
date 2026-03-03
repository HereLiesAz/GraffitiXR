#include <jni.h>
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include <GLES3/gl3.h>
#include "include/MobileGS.h"
#include "include/StereoProcessor.h"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "GraffitiJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GraffitiJNI", __VA_ARGS__)

// Global variables
MobileGS* gSlamEngine = nullptr;
StereoProcessor* gStereoProcessor = nullptr;
cv::Mat gLastColorFrame;
int gFrameCount = 0;
JavaVM* gJvm = nullptr;

// JNI OnLoad to cache the JVM
extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJvm = vm;
    LOGD("JNI_OnLoad: Cached JavaVM");
    return JNI_VERSION_1_6;
}

// Helper function to get JNIEnv for the current thread.
// Can be called from any C++ code in this shared library.
// It is the responsibility of the caller to detach the thread if it was attached.
JNIEnv* attachCurrentThreadAndGetEnv() {
    JNIEnv* env;
    if (gJvm == nullptr) {
        LOGE("Cannot attach thread, gJvm is null");
        return nullptr;
    }
    int getEnvStat = gJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(&env, NULL) != 0) {
            LOGE("Failed to attach current thread");
            return nullptr;
        }
        return env;
    } else if (getEnvStat == JNI_OK) {
        return env;
    } else {
        LOGE("GetEnv failed with error %d", getEnvStat);
        return nullptr;
    }
}

void detachCurrentThread() {
    if (gJvm != nullptr) {
        gJvm->DetachCurrentThread();
    }
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_ensureInitialized(JNIEnv* env, jobject thiz) {
    if (!gSlamEngine) {
        LOGD("Initializing MobileGS engine (CPU)");
        gSlamEngine = new MobileGS();
        gSlamEngine->initialize(1920, 1080);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initGl(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) {
        LOGD("Initializing MobileGS GL context");
        gSlamEngine->initGl();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroy(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) {
        LOGD("Destroying MobileGS engine");
        delete gSlamEngine;
        gSlamEngine = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_setArCoreTrackingState(JNIEnv* env, jobject thiz, jboolean isTracking) {
    if (gSlamEngine) {
        gSlamEngine->setArCoreTrackingState(isTracking);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateCamera(JNIEnv* env, jobject thiz, jfloatArray viewMatrix, jfloatArray projMatrix) {
    if (gSlamEngine) {
        jfloat* view = env->GetFloatArrayElements(viewMatrix, nullptr);
        jfloat* proj = env->GetFloatArrayElements(projMatrix, nullptr);
        gSlamEngine->updateCamera(view, proj);
        env->ReleaseFloatArrayElements(viewMatrix, view, JNI_ABORT);
        env->ReleaseFloatArrayElements(projMatrix, proj, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateAnchorTransform(JNIEnv* env, jobject thiz, jfloatArray transform) {
    if (gSlamEngine) {
        jfloat* mat = env->GetFloatArrayElements(transform, nullptr);
        gSlamEngine->updateAnchorTransform(mat);
        env->ReleaseFloatArrayElements(transform, mat, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedColorFrame(
        JNIEnv* env, jobject thiz, jobject colorBuffer, jint width, jint height) {

    uint8_t* buffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(colorBuffer));
    if (!buffer || !gSlamEngine) return;

    // Direct buffer is RGBA from ImageProcessingUtils.kt
    cv::Mat frame(height, width, CV_8UC4, buffer);
    cv::cvtColor(frame, gLastColorFrame, cv::COLOR_RGBA2RGB);

    if (gFrameCount % 100 == 0) {
        LOGD("JNI: Received color frame %d: %dx%d", gFrameCount, width, height);
    }

    if (!gSlamEngine->isTracking()) {
        gSlamEngine->attemptRelocalization(gLastColorFrame);
    }
    gFrameCount++;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedArCoreDepth(
        JNIEnv* env, jobject thiz, jobject depthBuffer, jint width, jint height) {

    if (!gSlamEngine) return;

    if (gLastColorFrame.empty()) {
        if (gFrameCount % 100 == 0) LOGD("JNI: Dropping depth frame: No color frame yet");
        return;
    }

    auto* rawDepth = static_cast<const uint16_t*>(env->GetDirectBufferAddress(depthBuffer));
    if (!rawDepth) return;

    cv::Mat depthMap(height, width, CV_32F, cv::Scalar(0.0f));
    int validPoints = 0;
    for (int r = 0; r < height; r++) {
        for (int c = 0; c < width; c++) {
            uint16_t raw = rawDepth[r * width + c];
            uint16_t depthMm = raw & 0x1FFFu;
            uint8_t conf = (raw >> 13u) & 0x7u;
            if (conf >= 1 && depthMm > 0) {
                depthMap.at<float>(r, c) = depthMm / 1000.0f;
                validPoints++;
            }
        }
    }

    if (gFrameCount % 100 == 0) {
        LOGD("JNI: Processing depth frame: %dx%d, valid: %d", width, height, validPoints);
    }

    if (depthMap.cols != gLastColorFrame.cols || depthMap.rows != gLastColorFrame.rows) {
        cv::resize(depthMap, depthMap, gLastColorFrame.size(), 0, 0, cv::INTER_NEAREST);
    }

    gSlamEngine->processDepthFrame(depthMap, gLastColorFrame);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_draw(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) {
        gSlamEngine->draw();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedStereoData(
        JNIEnv* env, jobject thiz, jobject leftBuffer, jobject rightBuffer, jint width, jint height) {
    if (!gStereoProcessor) gStereoProcessor = new StereoProcessor();
    auto* leftData = static_cast<int8_t*>(env->GetDirectBufferAddress(leftBuffer));
    auto* rightData = static_cast<int8_t*>(env->GetDirectBufferAddress(rightBuffer));
    if (!leftData || !rightData) return;
    gStereoProcessor->processStereo(leftData, rightData, width, height);
    cv::Mat disparity = gStereoProcessor->getDisparityMap();
    if (!disparity.empty() && gSlamEngine && !gLastColorFrame.empty()) {
        cv::Mat depthFromStereo;
        disparity.convertTo(depthFromStereo, CV_32F);
        gSlamEngine->processDepthFrame(depthFromStereo, gLastColorFrame);
    }
}

}
