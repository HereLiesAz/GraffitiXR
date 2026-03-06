#include <jni.h>
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <opencv2/opencv.hpp>
#include <GLES3/gl3.h>
#include "include/MobileGS.h"
#include "include/StereoProcessor.h"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "GraffitiJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GraffitiJNI", __VA_ARGS__)

MobileGS* gSlamEngine = nullptr;
StereoProcessor* gStereoProcessor = nullptr;
cv::Mat gLastColorFrame;
int gFrameCount = 0;
JavaVM* gJvm = nullptr;

// ---------------------------------------------------------
// Bitmap Conversion Helpers for Native Image Processing
// ---------------------------------------------------------
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
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeDestroy(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) {
        LOGD("Destroying MobileGS engine");
        delete gSlamEngine;
        gSlamEngine = nullptr;
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
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetViewportSize(JNIEnv* env, jobject thiz, jint width, jint height) {
    if (gSlamEngine) gSlamEngine->setViewportSize(width, height);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetRelocEnabled(JNIEnv* env, jobject thiz, jboolean enabled) {
    if (gSlamEngine) gSlamEngine->setRelocEnabled(enabled);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUpdateCamera(JNIEnv* env, jobject thiz, jfloatArray viewMatrix, jfloatArray projMatrix, jlong timestampNs) {
    if (gSlamEngine) {
        jfloat* view = env->GetFloatArrayElements(viewMatrix, nullptr);
        jfloat* proj = env->GetFloatArrayElements(projMatrix, nullptr);
        gSlamEngine->updateCamera(view, proj);
        env->ReleaseFloatArrayElements(viewMatrix, view, JNI_ABORT);
        env->ReleaseFloatArrayElements(projMatrix, proj, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUpdateLightLevel(JNIEnv* env, jobject thiz, jfloat level) {
    if (gSlamEngine) {
        // Automatically adjust feature detection sensitivity based on light level
        // Lower light level -> lower threshold (more sensitive)
        int threshold = (level < 20.0f) ? 10 : 31;
        // This is a simplified example; a real implementation would update the ORB/SuperPoint parameters.
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

    // Efficient YUV420 to RGB conversion using OpenCV
    cv::Mat yMat(height, width, CV_8UC1, yData, yStride);

    // For UV planes, we often have interleaved (NV21/NV12) or planar data.
    // ARCore typically provides planar YUV_420_888.
    // Simplification: if uvPixelStride is 2, it's likely NV21/NV12-like.
    // For a robust implementation, we'd handle all strides, but let's do a fast path for common ARCore output.

    if (gLastColorFrame.empty() || gLastColorFrame.cols != width || gLastColorFrame.rows != height) {
        gLastColorFrame = cv::Mat(height, width, CV_8UC3);
    }

    // Direct YUV to RGB conversion is much faster than the JPEG path.
    // Here we use a slightly simplified approach for the YUV_420_888 mapping.
    // In a production app, we'd use a dedicated shader or a highly optimized NEON kernel.
    cv::Mat yuv420;
    // ... (logic to merge planes into a format cv::cvtColor can handle)
    // For now, let's just use the Y plane as grayscale to verify it stops freezing,
    // then implement the full chrominance merge.
    cv::cvtColor(yMat, gLastColorFrame, cv::COLOR_GRAY2RGB);

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
    // Note: In a production VIO, timestampNs would be used to align IMU/Camera.
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedArCoreDepth(
        JNIEnv* env, jobject thiz, jobject depthBuffer, jint width, jint height, jint rowStride) {

    if (!gSlamEngine || gLastColorFrame.empty()) return;

    auto* rawDepthBytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(depthBuffer));
    if (!rawDepthBytes) return;

    cv::Mat depthMap(height, width, CV_32F, cv::Scalar(0.0f));

    for (int r = 0; r < height; r++) {
        auto* rowPtr = reinterpret_cast<const uint16_t*>(rawDepthBytes + (r * rowStride));
        for (int c = 0; c < width; c++) {
            uint16_t raw = rowPtr[c];
            uint16_t depthMm = raw & 0x1FFFu;
            uint8_t conf = (raw >> 13u) & 0x7u;
            if (conf >= 1 && depthMm > 0) {
                depthMap.at<float>(r, c) = depthMm / 1000.0f;
            }
        }
    }

    if (depthMap.cols != gLastColorFrame.cols || depthMap.rows != gLastColorFrame.rows) {
        cv::resize(depthMap, depthMap, gLastColorFrame.size(), 0, 0, cv::INTER_NEAREST);
    }

    gSlamEngine->pushFrame(depthMap, gLastColorFrame);
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

    if (!disparity.empty() && !gLastColorFrame.empty()) {
        cv::Mat depthFromStereo;
        // Normalize disparity to metric depth (simplified)
        // Focal length and baseline would be required for true metric depth.
        disparity.convertTo(depthFromStereo, CV_32F, 1.0/16.0); // StereoSGBM uses 16x fixed point
        gSlamEngine->pushFrame(depthFromStereo, gLastColorFrame);
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
    cv::Ptr<cv::ORB> detector = cv::ORB::create(500);
    detector->detectAndCompute(gray, cv::noArray(), kps, descs);

    if (descs.empty()) return nullptr;

    // Create Fingerprint object
    jclass fpClass = env->FindClass("com/hereliesaz/graffitixr/common/model/Fingerprint");
    jmethodID fpCtor = env->GetMethodID(fpClass, "<init>", "(Ljava/util/List;Ljava/util/List;[BIII)V");

    // Convert keypoints to List<KeyPoint>
    jclass kpClass = env->FindClass("org/opencv/core/KeyPoint");
    jmethodID kpCtor = env->GetMethodID(kpClass, "<init>", "(fffffffII)V");
    jclass listClass = env->FindClass("java/util/ArrayList");
    jmethodID listCtor = env->GetMethodID(listClass, "<init>", "(I)V");
    jmethodID addMethod = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");

    jobject kpList = env->NewObject(listClass, listCtor, (jint)kps.size());
    for (const auto& kp : kps) {
        jobject jkp = env->NewObject(kpClass, kpCtor, kp.pt.x, kp.pt.y, kp.size, kp.angle, kp.response, (jfloat)kp.octave, (jfloat)kp.class_id);
        env->CallBooleanMethod(kpList, addMethod, jkp);
        env->DeleteLocalRef(jkp);
    }

    // points3d (empty for initial capture)
    jobject ptsList = env->NewObject(listClass, listCtor, 0);

    // descriptorsData
    jsize descSize = descs.total() * descs.elemSize();
    jbyteArray jDescArray = env->NewByteArray(descSize);
    env->SetByteArrayRegion(jDescArray, 0, descSize, (const jbyte*)descs.data);

    jobject fpObj = env->NewObject(fpClass, fpCtor, kpList, ptsList, jDescArray, descs.rows, descs.cols, descs.type());

    return fpObj;
}

// ---------------------------------------------------------
// Advanced Image Processing Tools (Liquify, Heal, Burn)
// ---------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeApplyLiquify(
        JNIEnv* env, jobject thiz, jobject bitmap, jfloatArray points, jfloat radius, jfloat intensity) {
    cv::Mat src;
    bitmapToMat(env, bitmap, src);

    cv::Mat mapX(src.size(), CV_32FC1);
    cv::Mat mapY(src.size(), CV_32FC1);
    for (int y = 0; y < src.rows; y++) {
        for (int x = 0; x < src.cols; x++) {
            mapX.at<float>(y, x) = x;
            mapY.at<float>(y, x) = y;
        }
    }

    jsize len = env->GetArrayLength(points);
    jfloat* pts = env->GetFloatArrayElements(points, nullptr);

    for (int i = 0; i < len - 2; i += 2) {
        cv::Point2f p1(pts[i], pts[i+1]);
        cv::Point2f p2(pts[i+2], pts[i+3]);
        cv::Point2f dir = p2 - p1;

        int minX = std::max(0, (int)(std::min(p1.x, p2.x) - radius));
        int maxX = std::min(src.cols - 1, (int)(std::max(p1.x, p2.x) + radius));
        int minY = std::max(0, (int)(std::min(p1.y, p2.y) - radius));
        int maxY = std::min(src.rows - 1, (int)(std::max(p1.y, p2.y) + radius));

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float distSq = (x - p1.x)*(x - p1.x) + (y - p1.y)*(y - p1.y);
                if (distSq < radius * radius) {
                    float falloff = std::exp(-distSq / (radius * radius / 2.0f));
                    mapX.at<float>(y, x) -= dir.x * falloff * intensity;
                    mapY.at<float>(y, x) -= dir.y * falloff * intensity;
                }
            }
        }
    }

    cv::Mat dst;
    cv::remap(src, dst, mapX, mapY, cv::INTER_LINEAR, cv::BORDER_REPLICATE);
    matToBitmap(env, dst, bitmap);
    env->ReleaseFloatArrayElements(points, pts, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeApplyHeal(
        JNIEnv* env, jobject thiz, jobject bitmap, jfloatArray points, jfloat radius) {
    cv::Mat src;
    bitmapToMat(env, bitmap, src);

    jsize len = env->GetArrayLength(points);
    jfloat* pts = env->GetFloatArrayElements(points, nullptr);

    cv::Mat mask = cv::Mat::zeros(src.size(), CV_8UC1);
    for (int i = 0; i < len - 2; i += 2) {
        cv::Point pt1(pts[i], pts[i+1]);
        cv::Point pt2(pts[i+2], pts[i+3]);
        cv::line(mask, pt1, pt2, cv::Scalar(255), radius * 2, cv::LINE_8);
    }
    if (len == 2) {
        cv::circle(mask, cv::Point(pts[0], pts[1]), radius, cv::Scalar(255), -1);
    }

    cv::Mat dst;
    cv::Mat srcRGB;
    cv::cvtColor(src, srcRGB, cv::COLOR_RGBA2RGB);
    cv::inpaint(srcRGB, mask, dst, radius, cv::INPAINT_TELEA);

    cv::Mat dstRGBA;
    cv::cvtColor(dst, dstRGBA, cv::COLOR_RGB2RGBA);
    std::vector<cv::Mat> srcChannels, dstChannels;
    cv::split(src, srcChannels);
    cv::split(dstRGBA, dstChannels);
    dstChannels[3] = srcChannels[3]; // Restore original alpha
    cv::merge(dstChannels, dstRGBA);

    matToBitmap(env, dstRGBA, bitmap);
    env->ReleaseFloatArrayElements(points, pts, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeApplyBurnDodge(
        JNIEnv* env, jobject thiz, jobject bitmap, jfloatArray points, jfloat radius, jfloat intensity, jboolean isBurn) {
    cv::Mat src;
    bitmapToMat(env, bitmap, src);

    jsize len = env->GetArrayLength(points);
    jfloat* pts = env->GetFloatArrayElements(points, nullptr);

    cv::Mat mask = cv::Mat::zeros(src.size(), CV_32FC1);
    for (int i = 0; i < len - 2; i += 2) {
        cv::Point pt1(pts[i], pts[i+1]);
        cv::Point pt2(pts[i+2], pts[i+3]);
        cv::line(mask, pt1, pt2, cv::Scalar(1.0f), radius * 2, cv::LINE_8);
    }
    if (len == 2) {
        cv::circle(mask, cv::Point(pts[0], pts[1]), radius, cv::Scalar(1.0f), -1);
    }

    cv::GaussianBlur(mask, mask, cv::Size(0,0), radius / 2.0);

    for(int y = 0; y < src.rows; y++) {
        for(int x = 0; x < src.cols; x++) {
            float m = mask.at<float>(y,x);
            if (m > 0) {
                cv::Vec4b& px = src.at<cv::Vec4b>(y,x);
                float factor = isBurn ? (1.0f - intensity * m) : (1.0f + intensity * m);
                px[0] = cv::saturate_cast<uchar>(px[0] * factor);
                px[1] = cv::saturate_cast<uchar>(px[1] * factor);
                px[2] = cv::saturate_cast<uchar>(px[2] * factor);
            }
        }
    }

    matToBitmap(env, src, bitmap);
    env->ReleaseFloatArrayElements(points, pts, JNI_ABORT);
}

}