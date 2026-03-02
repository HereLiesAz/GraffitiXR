#include <jni.h>
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include <GLES3/gl3.h>
#include "MobileGS.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GraffitiJNI", __VA_ARGS__)

MobileGS* gSlamEngine = nullptr;
cv::Mat gLastColorFrame;

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_ensureInitialized(JNIEnv* env, jobject thiz) {
    if (!gSlamEngine) {
        gSlamEngine = new MobileGS();
        gSlamEngine->initialize(1920, 1080);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroy(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) {
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

    cv::Mat frame(height, width, CV_8UC4, buffer);
    cv::cvtColor(frame, gLastColorFrame, cv::COLOR_RGBA2RGB);

    // If ARCore is lost, pass the frame to attempt relocalization
    if (!gSlamEngine->isTracking()) {
        gSlamEngine->attemptRelocalization(gLastColorFrame);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedArCoreDepth(
        JNIEnv* env, jobject thiz, jobject depthBuffer, jint width, jint height) {

    if (!gSlamEngine || gLastColorFrame.empty()) return;

    auto* rawDepth = static_cast<const uint16_t*>(env->GetDirectBufferAddress(depthBuffer));
    if (!rawDepth) return;

    cv::Mat depthMap(height, width, CV_32F, cv::Scalar(0.0f));

    for (int r = 0; r < height; r++) {
        for (int c = 0; c < width; c++) {
            uint16_t raw = rawDepth[r * width + c];
            uint16_t depthMm = raw & 0x1FFFu;
            uint8_t conf = (raw >> 13u) & 0x7u;

            if (conf >= 4 && depthMm > 0) {
                depthMap.at<float>(r, c) = depthMm / 1000.0f;
            }
        }
    }

    if (depthMap.cols != gLastColorFrame.cols || depthMap.rows != gLastColorFrame.rows) {
        cv::resize(depthMap, depthMap, gLastColorFrame.size(), 0, 0, cv::INTER_NEAREST);
    }

    gSlamEngine->processDepthFrame(depthMap, gLastColorFrame);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_draw(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) {
        std::lock_guard<std::mutex> lock(gSlamEngine->getMutex());
        gSlamEngine->draw();
    }
}
}