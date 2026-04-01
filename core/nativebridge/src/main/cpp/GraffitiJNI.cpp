#include <jni.h>
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <opencv2/opencv.hpp>
#include <GLES3/gl3.h>
#include "include/MobileGS.h"
#include "include/StereoProcessor.h"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "GraffitiJNI", __VA_ARGS__)

static std::string gLastDepthTrace;
extern std::string gLastSplatTrace;
#define DEPTH_TRACE(fmt, ...) do {     char _buf[256];     snprintf(_buf, sizeof(_buf), fmt, ##__VA_ARGS__);     LOGD("DEPTH_PIPE: %s", _buf);     gLastDepthTrace += std::string(_buf) + "\n"; } while(0)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GraffitiJNI", __VA_ARGS__)

MobileGS* gSlamEngine = nullptr;
StereoProcessor* gStereoProcessor = nullptr;
cv::Mat gLastColorFrame;
int gFrameCount = 0;
JavaVM* gJvm = nullptr;

void bitmapToMat(JNIEnv * env, jobject bitmap, cv::Mat& dst) {
    AndroidBitmapInfo info;
    void* pixels = 0;
    AndroidBitmap_getInfo(env, bitmap, &info);
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    cv::Mat tmp(info.height, info.width, CV_8UC4, pixels);
    tmp.copyTo(dst);
    AndroidBitmap_unlockPixels(env, bitmap);
}

void matToBitmap(JNIEnv * env, cv::Mat& src, jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels = 0;
    AndroidBitmap_getInfo(env, bitmap, &info);
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    cv::Mat tmp(info.height, info.width, CV_8UC4, pixels);
    if(src.type() == CV_8UC4) {
        src.copyTo(tmp);
    } else if(src.type() == CV_8UC3) {
        cv::cvtColor(src, tmp, cv::COLOR_RGB2RGBA);
    } else if(src.type() == CV_8UC1) {
        cv::cvtColor(src, tmp, cv::COLOR_GRAY2RGBA);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJvm = vm;
    LOGD("JNI_OnLoad: Cached JavaVM");
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeInitialize(JNIEnv* env, jobject thiz) {
    if (!gSlamEngine) {
        LOGD("Initializing MobileGS engine (CPU)");
        gSlamEngine = new MobileGS();
        gSlamEngine->initialize(1920, 1080);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeInitGl(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) {
        LOGD("Initializing MobileGS GL context");
        gSlamEngine->initGl();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeResetGlContext(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) {
        LOGD("Resetting MobileGS GL context");
        gSlamEngine->resetGlContext();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeDestroy(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) {
        LOGD("Destroying MobileGS engine");
        delete gSlamEngine;
        gSlamEngine = nullptr;
    }
    if (gStereoProcessor) {
        LOGD("Destroying StereoProcessor");
        delete gStereoProcessor;
        gStereoProcessor = nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetSplatCount(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) return gSlamEngine->getSplatCount();
    return 0;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetArCoreTrackingState(JNIEnv* env, jobject thiz, jboolean isTracking) {
    if (gSlamEngine) gSlamEngine->setArCoreTrackingState(isTracking);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeClearMap(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) gSlamEngine->clearMap();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativePruneByConfidence(
        JNIEnv* env, jobject thiz, jfloat threshold) {
    if (gSlamEngine) gSlamEngine->pruneByConfidence(threshold);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetViewportSize(JNIEnv* env, jobject thiz, jint width, jint height) {
    if (gSlamEngine) gSlamEngine->setViewportSize(width, height);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetRelocEnabled(JNIEnv* env, jobject thiz, jboolean enabled) {
    if (gSlamEngine) gSlamEngine->setRelocEnabled(enabled);
}

float gLastViewMatrix[16];
float gLastProjMatrix[16];
float gLastMappingViewMatrix[16];
float gLastMappingProjMatrix[16];
bool gHasCameraMatrices = false;

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUpdateCamera(
        JNIEnv* env, jobject thiz,
        jfloatArray viewMatrix, jfloatArray projMatrix,
        jfloatArray mappingViewMatrix, jfloatArray mappingProjMatrix,
        jlong timestampNs) {
    if (gSlamEngine) {
        jfloat* view = env->GetFloatArrayElements(viewMatrix, nullptr);
        jfloat* proj = env->GetFloatArrayElements(projMatrix, nullptr);
        jfloat* mView = env->GetFloatArrayElements(mappingViewMatrix, nullptr);
        jfloat* mProj = env->GetFloatArrayElements(mappingProjMatrix, nullptr);

        gSlamEngine->updateCamera(view, proj);
        gSlamEngine->updateMappingCamera(mView, mProj);

        memcpy(gLastViewMatrix, view, 16 * sizeof(float));
        memcpy(gLastProjMatrix, proj, 16 * sizeof(float));
        memcpy(gLastMappingViewMatrix, mView, 16 * sizeof(float));
        memcpy(gLastMappingProjMatrix, mProj, 16 * sizeof(float));
        gHasCameraMatrices = true;

        env->ReleaseFloatArrayElements(viewMatrix, view, JNI_ABORT);
        env->ReleaseFloatArrayElements(projMatrix, proj, JNI_ABORT);
        env->ReleaseFloatArrayElements(mappingViewMatrix, mView, JNI_ABORT);
        env->ReleaseFloatArrayElements(mappingProjMatrix, mProj, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUpdateLightLevel(JNIEnv* env, jobject thiz, jfloat level) {
    if (gSlamEngine) {
        gSlamEngine->updateLightLevel(level);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUpdateAnchorTransform(JNIEnv* env, jobject thiz, jfloatArray transform) {
    if (gSlamEngine) {
        jfloat* mat = env->GetFloatArrayElements(transform, nullptr);
        gSlamEngine->updateAnchorTransform(mat);
        env->ReleaseFloatArrayElements(transform, mat, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedYuvFrame(
        JNIEnv* env, jobject thiz, jobject yBuffer, jobject uBuffer, jobject vBuffer,
        jint width, jint height, jint yStride, jint uvStride, jint uvPixelStride, jlong timestampNs) {

    if (!gSlamEngine) return;

    uint8_t* yData = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    uint8_t* uData = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    uint8_t* vData = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));

    if (!yData || !uData || !vData) return;

    cv::Mat yMat(height, width, CV_8UC1, yData, yStride);
    cv::Mat uMat(height / 2, width / 2, CV_8UC1, uData, uvStride);
    cv::Mat vMat(height / 2, width / 2, CV_8UC1, vData, uvStride);

    if (gLastColorFrame.empty() || gLastColorFrame.cols != width || gLastColorFrame.rows != height) {
        gLastColorFrame = cv::Mat(height, width, CV_8UC3);
    }

    cv::Mat yuv(height + height / 2, width, CV_8UC1);
    yMat.copyTo(yuv(cv::Rect(0, 0, width, height)));

    if (uvPixelStride == 1) {
        uMat.copyTo(yuv(cv::Rect(0, height, width / 2, height / 4)));
        vMat.copyTo(yuv(cv::Rect(width / 2, height, width / 2, height / 4)));
        cv::cvtColor(yuv, gLastColorFrame, cv::COLOR_YUV2RGB_I420);
    } else if (uvPixelStride == 2) {
        cv::Mat uvInterleaved(height / 2, width, CV_8UC1, vData, uvStride);
        uvInterleaved.copyTo(yuv(cv::Rect(0, height, width, height / 2)));
        cv::cvtColor(yuv, gLastColorFrame, cv::COLOR_YUV2RGB_NV21);
    } else {
        cv::cvtColor(yMat, gLastColorFrame, cv::COLOR_GRAY2RGB);
    }

    gSlamEngine->scheduleRelocCheck(gLastColorFrame);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedColorFrame(
        JNIEnv* env, jobject thiz, jobject colorBuffer, jint width, jint height, jlong timestampNs) {

    uint8_t* buffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(colorBuffer));
    if (!buffer || !gSlamEngine) return;

    cv::Mat frame(height, width, CV_8UC4, buffer);
    cv::cvtColor(frame, gLastColorFrame, cv::COLOR_RGBA2RGB);

    gSlamEngine->scheduleRelocCheck(gLastColorFrame);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedArCoreDepth(
        JNIEnv* env, jobject thiz, jobject depthBuffer, jint width, jint height, jint rowStride, jfloatArray intrArray, jint cpuW, jint cpuH) {

    gLastDepthTrace.clear();
    DEPTH_TRACE("feedArCoreDepth called w=%d h=%d stride=%d", width, height, rowStride);

    if (!gSlamEngine) { DEPTH_TRACE("DROPPED - no engine"); return; }
    if (gLastColorFrame.empty()) { DEPTH_TRACE("DROPPED - no color frame yet"); return; }

    auto* rawDepthBytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(depthBuffer));
    if (!rawDepthBytes) { DEPTH_TRACE("DROPPED - null buffer"); return; }

    cv::Mat depthMap(height, width, CV_32F, cv::Scalar(0.0f));

    int validPixels = 0;
    int zeroConfPixels = 0;
    float minD = 999.f, maxD = 0.f;

    for (int r = 0; r < height; r++) {
        auto* rowPtr = reinterpret_cast<const uint16_t*>(rawDepthBytes + (r * rowStride));
        for (int c = 0; c < width; c++) {
            uint16_t raw = rowPtr[c];
            uint16_t depthMm = raw & 0x1FFFu;
            uint8_t conf = (raw >> 13u) & 0x7u;
            if (depthMm > 0) {
                float d = depthMm / 1000.0f;
                depthMap.at<float>(r, c) = d;
                validPixels++;
                if (d < minD) minD = d;
                if (d > maxD) maxD = d;
            } else if (conf == 0) {
                zeroConfPixels++;
            }
        }
    }

    DEPTH_TRACE("decoded valid=%d zeroConf=%d range=%.2f-%.2fm", validPixels, zeroConfPixels, minD, maxD);

    if (validPixels == 0) {
        DEPTH_TRACE("DROPPED - all pixels invalid (no depth data)");
        return;
    }

    jfloat* intr = env->GetFloatArrayElements(intrArray, nullptr);
    float fx = intr[0], fy = intr[1], cx = intr[2], cy = intr[3];
    env->ReleaseFloatArrayElements(intrArray, intr, JNI_ABORT);

    if (cpuW > 0 && cpuH > 0) {
        float scaleX = (float)depthMap.cols / cpuW;
        float scaleY = (float)depthMap.rows / cpuH;
        fx *= scaleX;
        fy *= scaleY;
        cx *= scaleX;
        cy *= scaleY;
    }

    float finalIntrinsics[4] = {fx, fy, cx, cy};

    if (!gHasCameraMatrices) {
        DEPTH_TRACE("DROPPED - no camera matrices yet");
        return;
    }

    DEPTH_TRACE("pushing frame to map thread depthSize=%dx%d", depthMap.cols, depthMap.rows);
    gSlamEngine->pushFrame(depthMap, gLastColorFrame, gLastMappingViewMatrix, gLastMappingProjMatrix, finalIntrinsics, false);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeDraw(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) gSlamEngine->draw();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedStereoData(
        JNIEnv* env, jobject thiz, jobject leftBuffer, jobject rightBuffer, jint width, jint height, jlong timestamp) {

    if (!gSlamEngine) return;

    if (!gStereoProcessor) gStereoProcessor = new StereoProcessor();

    auto* leftData = static_cast<int8_t*>(env->GetDirectBufferAddress(leftBuffer));
    auto* rightData = static_cast<int8_t*>(env->GetDirectBufferAddress(rightBuffer));
    if (!leftData || !rightData) return;

    gStereoProcessor->processStereo(leftData, rightData, width, height);
    cv::Mat disparity = gStereoProcessor->getDisparityMap();

    if (!disparity.empty() && !gLastColorFrame.empty() && gHasCameraMatrices) {
        cv::Mat depthFromStereo;
        disparity.convertTo(depthFromStereo, CV_32F, 1.0/16.0);
        gSlamEngine->pushFrame(depthFromStereo, gLastColorFrame, gLastMappingViewMatrix, gLastMappingProjMatrix, nullptr, false);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSaveModel(JNIEnv* env, jobject thiz, jstring pathStr) {
    if (gSlamEngine) {
        const char* path = env->GetStringUTFChars(pathStr, nullptr);
        gSlamEngine->saveModel(path);
        env->ReleaseStringUTFChars(pathStr, path);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeLoadModel(JNIEnv* env, jobject thiz, jstring pathStr) {
    if (gSlamEngine) {
        const char* path = env->GetStringUTFChars(pathStr, nullptr);
        gSlamEngine->loadModel(path);
        env->ReleaseStringUTFChars(pathStr, path);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeLoadSuperPoint(
        JNIEnv* env, jobject thiz, jobject assetManager) {
    if (!gSlamEngine) return JNI_FALSE;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    AAsset* asset = AAssetManager_open(mgr, "superpoint.onnx", AASSET_MODE_BUFFER);
    if (!asset) {
        LOGD("superpoint.onnx not found in assets — ORB fallback active");
        return JNI_FALSE;
    }

    size_t size = (size_t)AAsset_getLength(asset);
    std::vector<uchar> buf(size);
    AAsset_read(asset, buf.data(), (off_t)size);
    AAsset_close(asset);

    bool ok = gSlamEngine->loadSuperPoint(buf);
    LOGD("SuperPoint init: %s", ok ? "ready" : "FAILED — ORB fallback active");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetTargetFingerprint(
        JNIEnv* env, jobject thiz, jbyteArray descArray, jint rows, jint cols, jint type, jfloatArray ptsArray) {
    if (gSlamEngine) {
        jbyte* descData = env->GetByteArrayElements(descArray, nullptr);
        cv::Mat descriptors(rows, cols, type, descData);

        jsize ptsLen = env->GetArrayLength(ptsArray);
        jfloat* ptsData = env->GetFloatArrayElements(ptsArray, nullptr);

        std::vector<cv::Point3f> points3d;
        for (int i = 0; i < ptsLen; i += 3) {
            points3d.push_back(cv::Point3f(ptsData[i], ptsData[i+1], ptsData[i+2]));
        }

        gSlamEngine->setTargetFingerprint(descriptors, points3d);

        env->ReleaseByteArrayElements(descArray, descData, JNI_ABORT);
        env->ReleaseFloatArrayElements(ptsArray, ptsData, JNI_ABORT);
    }
}

static jobject buildFingerprintObject(JNIEnv* env,
        const std::vector<cv::KeyPoint>& kps,
        const cv::Mat& descs) {
    if (descs.empty()) return nullptr;

    jclass fpClass  = env->FindClass("com/hereliesaz/graffitixr/common/model/Fingerprint");
    jmethodID fpCtor = env->GetMethodID(fpClass, "<init>", "(Ljava/util/List;Ljava/util/List;[BIII)V");

    jclass kpClass   = env->FindClass("org/opencv/core/KeyPoint");
    jmethodID kpCtor = env->GetMethodID(kpClass, "<init>", "(FFFFFII)V");

    jclass listClass    = env->FindClass("java/util/ArrayList");
    jmethodID listCtor  = env->GetMethodID(listClass, "<init>", "(I)V");
    jmethodID addMethod = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");

    jobject kpList = env->NewObject(listClass, listCtor, (jint)kps.size());
    for (const auto& kp : kps) {
        jobject jkp = env->NewObject(kpClass, kpCtor,
                kp.pt.x, kp.pt.y, kp.size, kp.angle, kp.response,
                (jint)kp.octave, (jint)kp.class_id);
        env->CallBooleanMethod(kpList, addMethod, jkp);
        env->DeleteLocalRef(jkp);
    }

    jobject ptsList = env->NewObject(listClass, listCtor, 0);

    jsize descSize = descs.total() * descs.elemSize();
    jbyteArray jDescArray = env->NewByteArray(descSize);
    env->SetByteArrayRegion(jDescArray, 0, descSize, (const jbyte*)descs.data);

    return env->NewObject(fpClass, fpCtor,
            kpList, ptsList, jDescArray,
            descs.rows, descs.cols, descs.type());
}

JNIEXPORT jobject JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGenerateFingerprint(
        JNIEnv* env, jobject thiz, jobject bitmap) {

    cv::Mat frame;
    bitmapToMat(env, bitmap, frame);
    if (frame.empty()) return nullptr;

    cv::Mat gray;
    cv::cvtColor(frame, gray, cv::COLOR_RGBA2GRAY);

    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;
    cv::ORB::create(500)->detectAndCompute(gray, cv::noArray(), kps, descs);

    return buildFingerprintObject(env, kps, descs);
}

JNIEXPORT jobject JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGenerateFingerprintMasked(
        JNIEnv* env, jobject thiz, jobject bitmap, jobject maskBitmap) {

    cv::Mat frame;
    bitmapToMat(env, bitmap, frame);
    if (frame.empty()) return nullptr;

    cv::Mat gray;
    cv::cvtColor(frame, gray, cv::COLOR_RGBA2GRAY);

    cv::Mat orbMask;
    if (maskBitmap != nullptr) {
        cv::Mat maskRgba;
        bitmapToMat(env, maskBitmap, maskRgba);
        if (!maskRgba.empty()) {
            cv::cvtColor(maskRgba, orbMask, cv::COLOR_RGBA2GRAY);
            if (orbMask.size() != gray.size()) {
                cv::resize(orbMask, orbMask, gray.size(), 0, 0, cv::INTER_NEAREST);
            }
        }
    }

    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;
    cv::ORB::create(500)->detectAndCompute(gray, orbMask, kps, descs);

    return buildFingerprintObject(env, kps, descs);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeAnnotateKeypoints(
        JNIEnv* env, jobject thiz, jobject bitmap) {
    cv::Mat frame;
    bitmapToMat(env, bitmap, frame);
    if (frame.empty()) return;

    cv::Mat gray;
    cv::cvtColor(frame, gray, cv::COLOR_RGBA2GRAY);

    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;
    cv::ORB::create(500)->detectAndCompute(gray, cv::noArray(), kps, descs);

    cv::Mat overlay = frame.clone();
    for (const auto& kp : kps) {
        int r = std::max(6, (int)(kp.size * 2.0f));
        cv::circle(overlay, cv::Point((int)kp.pt.x, (int)kp.pt.y),
                r, cv::Scalar(0, 210, 50, 255), cv::FILLED);
    }
    cv::addWeighted(frame, 0.55, overlay, 0.45, 0, frame);

    std::string label = std::to_string(kps.size()) + " features";
    double scale = std::max(1.0, frame.cols / 640.0);
    int baseline = 0;
    cv::Size textSize = cv::getTextSize(label, cv::FONT_HERSHEY_SIMPLEX, scale, 2, &baseline);
    cv::rectangle(frame,
            cv::Point(8, 8),
            cv::Point(textSize.width + 16, textSize.height + baseline + 16),
            cv::Scalar(0, 0, 0, 200), cv::FILLED);
    cv::putText(frame, label,
            cv::Point(12, textSize.height + 12),
            cv::FONT_HERSHEY_SIMPLEX, scale,
            cv::Scalar(255, 220, 0, 255), 2);

    matToBitmap(env, frame, bitmap);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetLastDepthTrace(
        JNIEnv* env, jobject) {
    return env->NewStringUTF(gLastDepthTrace.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetLastSplatTrace(
        JNIEnv* env, jobject) {
    return env->NewStringUTF(gLastSplatTrace.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetSplatsVisible(
        JNIEnv* env, jobject, jboolean visible) {
    if (gSlamEngine) gSlamEngine->setSplatsVisible(visible);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetAnchorTransform(
        JNIEnv* env, jobject) {
    jfloatArray result = env->NewFloatArray(16);
    if (gSlamEngine) {
        float mat[16];
        gSlamEngine->getAnchorTransform(mat);
        env->SetFloatArrayRegion(result, 0, 16, mat);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeAddLayerFeatures(
        JNIEnv* env, jobject,
        jobject bitmap,
        jobject depthBuffer, jint depthW, jint depthH, jint depthStride,
        jfloatArray intrinsicsArray,
        jfloatArray viewMatArray) {

    if (!gSlamEngine) return;

    cv::Mat composite;
    bitmapToMat(env, bitmap, composite);
    if (composite.empty()) return;

    auto* depthData = static_cast<const uint8_t*>(env->GetDirectBufferAddress(depthBuffer));
    if (!depthData) return;

    jfloat* intr = env->GetFloatArrayElements(intrinsicsArray, nullptr);
    jfloat* view = env->GetFloatArrayElements(viewMatArray, nullptr);

    gSlamEngine->addLayerFeatures(composite, depthData, depthW, depthH, depthStride,
            intr, view);

    env->ReleaseFloatArrayElements(intrinsicsArray, intr, JNI_ABORT);
    env->ReleaseFloatArrayElements(viewMatArray, view, JNI_ABORT);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetPaintingProgress(
        JNIEnv*, jobject) {
    return gSlamEngine ? gSlamEngine->getPaintingProgress() : 0.0f;
}

} // extern "C"