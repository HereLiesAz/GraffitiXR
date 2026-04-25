#include <jni.h>
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <opencv2/opencv.hpp>
#include <GLES3/gl3.h>
#include "include/MobileGS.h"
#include "include/SurfaceUnroller.h"
#include "include/StereoProcessor.h"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "GraffitiJNI", __VA_ARGS__)

static std::string gLastDepthTrace;
static std::string gLastSplatTrace;
#define DEPTH_TRACE(fmt, ...) do {     char _buf[256];     snprintf(_buf, sizeof(_buf), fmt, ##__VA_ARGS__);     LOGD("DEPTH_PIPE: %s", _buf);     gLastDepthTrace += std::string(_buf) + "\n"; } while(0)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GraffitiJNI", __VA_ARGS__)

MobileGS* gSlamEngine = nullptr;
StereoProcessor* gStereoProcessor = nullptr;
cv::Mat gLastColorFrame; // MANDATE: Kept in Sensor-Native (Landscape) orientation
int gFrameCount = 0;
JavaVM* gJvm = nullptr;

static int gColorImageWidth  = 0;
static int gColorImageHeight = 0;

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
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT jfloat JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetVisibleConfidenceAvg(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) {
        float vis, glob;
        gSlamEngine->getConfidenceAvgs(vis, glob);
        return vis;
    }
    return 0.0f;
}

JNIEXPORT jfloat JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetGlobalConfidenceAvg(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) {
        float vis, glob;
        gSlamEngine->getConfidenceAvgs(vis, glob);
        return glob;
    }
    return 0.0f;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUpdateDeviceMotion(JNIEnv* env, jobject thiz, jfloatArray angularVel, jfloatArray linearVel) {
    if (gSlamEngine) {
        jfloat* a = env->GetFloatArrayElements(angularVel, nullptr);
        jfloat* l = env->GetFloatArrayElements(linearVel, nullptr);
        gSlamEngine->updateDeviceMotion(a, l);
        env->ReleaseFloatArrayElements(angularVel, a, JNI_ABORT);
        env->ReleaseFloatArrayElements(linearVel, l, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeInitialize(JNIEnv* env, jobject thiz) {
    if (!gSlamEngine) {
        gSlamEngine = new MobileGS();
        gSlamEngine->initialize(1920, 1080);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeInitGl(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) gSlamEngine->initGl();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeResetGlContext(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) gSlamEngine->resetGlContext();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeDestroy(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) { delete gSlamEngine; gSlamEngine = nullptr; }
    if (gStereoProcessor) { delete gStereoProcessor; gStereoProcessor = nullptr; }
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetSplatCount(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) return gSlamEngine->getSplatCount();
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetImmutableSplatCount(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) return gSlamEngine->getImmutableSplatCount();
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
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativePruneByConfidence(JNIEnv* env, jobject thiz, jfloat threshold) {
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

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetVoxelSize(JNIEnv* env, jobject thiz, jfloat size) {
    if (gSlamEngine) gSlamEngine->setVoxelSize(size);
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
    if (gSlamEngine) gSlamEngine->updateLightLevel(level);
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
        jint width, jint height, jint yStride, jint uvStride, jint uvPixelStride, jlong timestampNs, jint cvRotateCode) {

    if (!gSlamEngine) return;

    uint8_t* yData = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    uint8_t* uData = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    uint8_t* vData = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));

    if (!yData || !uData || !vData) return;

    gColorImageWidth  = width;
    gColorImageHeight = height;

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
        // No conversion on GL thread; pass raw YUV to map thread
        gLastColorFrame = yuv.clone();
    } else if (uvPixelStride == 2) {
        cv::Mat uvInterleaved(height / 2, width, CV_8UC1, vData, uvStride);
        uvInterleaved.copyTo(yuv(cv::Rect(0, height, width, height / 2)));
        // No conversion on GL thread; pass raw YUV to map thread
        gLastColorFrame = yuv.clone();
    } else {
        cv::cvtColor(yMat, gLastColorFrame, cv::COLOR_GRAY2RGB);
    }

    // Relocalization MATCHING still uses the Display-Aligned frame for best user feedback
    if (!gLastColorFrame.empty() && gLastColorFrame.rows == height + height/2) {
        cv::Mat relocFrame;
        cv::cvtColor(gLastColorFrame, relocFrame, cv::COLOR_YUV2RGB_NV21);
        if (cvRotateCode >= 0) {
            cv::rotate(relocFrame, relocFrame, cvRotateCode);
        }
        gSlamEngine->scheduleRelocCheck(relocFrame);
    } else if (!gLastColorFrame.empty()) {
        cv::Mat relocFrame = gLastColorFrame.clone();
        if (cvRotateCode >= 0) {
            cv::rotate(relocFrame, relocFrame, cvRotateCode);
        }
        gSlamEngine->scheduleRelocCheck(relocFrame);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedColorFrame(
        JNIEnv* env, jobject thiz, jobject colorBuffer, jint width, jint height, jlong timestampNs, jint cvRotateCode) {

    uint8_t* buffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(colorBuffer));
    if (!buffer || !gSlamEngine) return;

    gColorImageWidth  = width;
    gColorImageHeight = height;

    cv::Mat frame(height, width, CV_8UC4, buffer);
    cv::cvtColor(frame, gLastColorFrame, cv::COLOR_RGBA2RGB);

    cv::Mat relocFrame = gLastColorFrame.clone();
    if (cvRotateCode >= 0) {
        cv::rotate(relocFrame, relocFrame, cvRotateCode);
    }
    gSlamEngine->scheduleRelocCheck(relocFrame);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedPointCloud(JNIEnv* env, jobject thiz, jfloatArray points) {
    if (gSlamEngine) {
        jsize len = env->GetArrayLength(points);
        jfloat* ptr = env->GetFloatArrayElements(points, nullptr);
        std::vector<float> pts(ptr, ptr + len);
        gSlamEngine->pushPointCloud(pts);
        env->ReleaseFloatArrayElements(points, ptr, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedArCoreDepth(
        JNIEnv* env, jobject thiz, jobject depthBuffer, jint width, jint height, jint rowStride, jfloatArray intrArray, jint cpuW, jint cpuH, jint cvRotateCode) {

    gLastDepthTrace.clear();
    if (!gSlamEngine) return;
    if (gLastColorFrame.empty()) return;

    auto* rawDepthBytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(depthBuffer));
    if (!rawDepthBytes) return;

    // MANDATE: Keep depth map in sensor-native (Landscape) orientation to align with Physical pose.
    cv::Mat depthMap(height, width, CV_32F, cv::Scalar(0.0f));

    int validPixels = 0;
    for (int r = 0; r < height; r++) {
        auto* rowPtr = reinterpret_cast<const uint16_t*>(rawDepthBytes + (r * rowStride));
        for (int c = 0; c < width; c++) {
            uint16_t raw = rowPtr[c];
            uint16_t depthMm = raw & 0x1FFFu;
            uint8_t conf = (raw >> 13u) & 0x7u;
            if (depthMm > 0 && conf > 0) {
                depthMap.at<float>(r, c) = (float)depthMm / 1000.0f;
                validPixels++;
            }
        }
    }

    if (validPixels == 0) return;

    jfloat* intr = env->GetFloatArrayElements(intrArray, nullptr);
    float fx = intr[0], fy = intr[1], cx = intr[2], cy = intr[3];
    env->ReleaseFloatArrayElements(intrArray, intr, JNI_ABORT);

    // SCALE: Physical intrinsics are for the full CPU resolution (cpuW/cpuH).
    // They must be scaled to match the sensor-native depth resolution (width/height).
    if (cpuW > 0 && cpuH > 0) {
        float scaleX = (float)width / (float)cpuW;
        float scaleY = (float)height / (float)cpuH;
        fx *= scaleX; fy *= scaleY;
        cx *= scaleX; cy *= scaleY;
    }

    float finalIntrinsics[4] = {fx, fy, cx, cy};

    if (!gHasCameraMatrices) return;

    bool isYuv = (gLastColorFrame.rows == gColorImageHeight + gColorImageHeight / 2);

    // MANDATE: Pass sensor-native depth map with Physical Pose (gLastMappingViewMatrix)
    gSlamEngine->pushFrame(depthMap, gLastColorFrame, gLastMappingViewMatrix, gLastMappingProjMatrix, finalIntrinsics, isYuv);
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
        bool isYuv = (gLastColorFrame.rows == gColorImageHeight + gColorImageHeight / 2);
        gSlamEngine->pushFrame(depthFromStereo, gLastColorFrame, gLastMappingViewMatrix, gLastMappingProjMatrix, nullptr, isYuv);
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
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeImportModel3D(JNIEnv* env, jobject thiz, jstring pathStr) {
    if (gSlamEngine) {
        const char* path = env->GetStringUTFChars(pathStr, nullptr);
        bool ok = gSlamEngine->importModel3D(path);
        env->ReleaseStringUTFChars(pathStr, path);
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeLoadSuperPoint(
        JNIEnv* env, jobject thiz, jobject assetManager) {
    if (!gSlamEngine) return JNI_FALSE;
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    AAsset* asset = AAssetManager_open(mgr, "superpoint.onnx", AASSET_MODE_BUFFER);
    if (!asset) return JNI_FALSE;
    size_t size = (size_t)AAsset_getLength(asset);
    std::vector<uchar> buf(size);
    AAsset_read(asset, buf.data(), (off_t)size);
    AAsset_close(asset);
    bool ok = gSlamEngine->loadSuperPoint(buf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeRestoreWallFingerprint(
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
        gSlamEngine->restoreWallFingerprint(descriptors, points3d);
        env->ReleaseByteArrayElements(descArray, descData, JNI_ABORT);
        env->ReleaseFloatArrayElements(ptsArray, ptsData, JNI_ABORT);
    }
}

jobject buildFingerprintObject(JNIEnv* env, const MobileGS::FingerprintData& fd) {
    if (fd.descriptors.empty()) return nullptr;

    jclass listClass = env->FindClass("java/util/ArrayList");
    jmethodID listCtor = env->GetMethodID(listClass, "<init>", "(I)V");
    jmethodID addMethod = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");

    // Keypoints: List<org.opencv.core.KeyPoint>
    jclass kpClass = env->FindClass("org/opencv/core/KeyPoint");
    jmethodID kpCtor = env->GetMethodID(kpClass, "<init>", "(FFFFFII)V");
    jobject kpList = env->NewObject(listClass, listCtor, (jint)fd.keypoints.size());
    for (const auto& kp : fd.keypoints) {
        jobject jkp = env->NewObject(kpClass, kpCtor, kp.pt.x, kp.pt.y, kp.size, kp.angle, kp.response, (jint)kp.octave, (jint)kp.class_id);
        env->CallBooleanMethod(kpList, addMethod, jkp);
        env->DeleteLocalRef(jkp);
    }

    // Points3D: List<Float>
    jclass floatClass = env->FindClass("java/lang/Float");
    jmethodID floatCtor = env->GetMethodID(floatClass, "<init>", "(F)V");
    jobject ptsList = env->NewObject(listClass, listCtor, (jint)fd.points3d.size());
    for (float f : fd.points3d) {
        jobject jf = env->NewObject(floatClass, floatCtor, f);
        env->CallBooleanMethod(ptsList, addMethod, jf);
        env->DeleteLocalRef(jf);
    }

    // DescriptorsData: byte[]
    jsize descSize = fd.descriptors.total() * fd.descriptors.elemSize();
    jbyteArray descArray = env->NewByteArray(descSize);
    env->SetByteArrayRegion(descArray, 0, descSize, (const jbyte*)fd.descriptors.data);

    jclass fpClass = env->FindClass("com/hereliesaz/graffitixr/common/model/Fingerprint");
    jmethodID fpCtor = env->GetMethodID(fpClass, "<init>", "(Ljava/util/List;Ljava/util/List;[BIII)V");

    jobject fpObj = env->NewObject(fpClass, fpCtor, kpList, ptsList, descArray, fd.descriptors.rows, fd.descriptors.cols, fd.descriptors.type());

    return fpObj;
}

JNIEXPORT jobject JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetWallFingerprint(
        JNIEnv* env, jobject thiz, jobject bitmap, jobject mask, jobject depthBuffer, jint depthW, jint depthH, jint depthStride, jfloatArray intrArray, jfloatArray viewMatArray) {

    if (!gSlamEngine) return nullptr;

    cv::Mat image;
    bitmapToMat(env, bitmap, image);

    cv::Mat maskMat;
    if (mask) bitmapToMat(env, mask, maskMat);

    auto* depthData = static_cast<const uint8_t*>(env->GetDirectBufferAddress(depthBuffer));
    jfloat* intr = env->GetFloatArrayElements(intrArray, nullptr);
    jfloat* view = env->GetFloatArrayElements(viewMatArray, nullptr);

    MobileGS::FingerprintData fd = gSlamEngine->generateFingerprint(image, maskMat, depthData, depthW, depthH, depthStride, intr, view);

    env->ReleaseFloatArrayElements(intrArray, intr, JNI_ABORT);
    env->ReleaseFloatArrayElements(viewMatArray, view, JNI_ABORT);

    return buildFingerprintObject(env, fd);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetArtworkFingerprint(
        JNIEnv* env, jobject thiz, jobject bitmap, jobject depthBuffer, jint depthW, jint depthH, jint depthStride, jfloatArray intrArray, jfloatArray viewMatArray) {
    if (gSlamEngine) {
        cv::Mat composite;
        bitmapToMat(env, bitmap, composite);
        auto* depthData = static_cast<const uint8_t*>(env->GetDirectBufferAddress(depthBuffer));
        jfloat* intr = env->GetFloatArrayElements(intrArray, nullptr);
        jfloat* view = env->GetFloatArrayElements(viewMatArray, nullptr);
        gSlamEngine->setArtworkFingerprint(composite, depthData, depthW, depthH, depthStride, intr, view);
        env->ReleaseFloatArrayElements(intrArray, intr, JNI_ABORT);
        env->ReleaseFloatArrayElements(viewMatArray, view, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeAnnotateKeypoints(
        JNIEnv* env, jobject thiz, jobject bitmap) {
    cv::Mat frame;
    bitmapToMat(env, bitmap, frame);
    if (frame.empty()) return;

    cv::Mat gray;
    if (frame.channels() == 4) cv::cvtColor(frame, gray, cv::COLOR_RGBA2GRAY);
    else if (frame.channels() == 3) cv::cvtColor(frame, gray, cv::COLOR_RGB2GRAY);
    else gray = frame.clone();

    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;
    if (gSlamEngine) {
        gSlamEngine->getMutex().lock();
        // Use consistent feature detection for visualization
        cv::ORB::create(500)->detectAndCompute(gray, cv::noArray(), kps, descs);
        gSlamEngine->getMutex().unlock();
    }

    // Convert frame to RGBA if it isn't already for drawing
    cv::Mat annotated;
    if (frame.channels() == 4) annotated = frame.clone();
    else if (frame.channels() == 3) cv::cvtColor(frame, annotated, cv::COLOR_RGB2RGBA);
    else cv::cvtColor(frame, annotated, cv::COLOR_GRAY2RGBA);

    cv::drawKeypoints(annotated, kps, annotated, cv::Scalar(0, 255, 0, 255), cv::DrawMatchesFlags::DRAW_RICH_KEYPOINTS);

    matToBitmap(env, annotated, bitmap);
}

JNIEXPORT jfloatArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetKeypoints(
        JNIEnv* env, jobject thiz, jobject bitmap) {
    cv::Mat frame;
    bitmapToMat(env, bitmap, frame);
    if (frame.empty()) return nullptr;

    cv::Mat gray;
    if (frame.channels() == 4) cv::cvtColor(frame, gray, cv::COLOR_RGBA2GRAY);
    else if (frame.channels() == 3) cv::cvtColor(frame, gray, cv::COLOR_RGB2GRAY);
    else gray = frame.clone();

    std::vector<cv::KeyPoint> kps;
    if (gSlamEngine) {
        gSlamEngine->getMutex().lock();
        cv::ORB::create(500)->detect(gray, kps);
        gSlamEngine->getMutex().unlock();
    }

    jfloatArray result = env->NewFloatArray(kps.size() * 2);
    jfloat* ptr = env->GetFloatArrayElements(result, nullptr);
    for (size_t i = 0; i < kps.size(); ++i) {
        ptr[i * 2] = kps[i].pt.x;
        ptr[i * 2 + 1] = kps[i].pt.y;
    }
    env->ReleaseFloatArrayElements(result, ptr, 0);

    return result;
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetLastDepthTrace(JNIEnv* env, jobject) {
    return env->NewStringUTF(gLastDepthTrace.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetLastSplatTrace(JNIEnv* env, jobject) {
    return env->NewStringUTF(gLastSplatTrace.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetSplatsVisible(JNIEnv* env, jobject, jboolean visible) {
    if (gSlamEngine) gSlamEngine->setSplatsVisible(visible);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetAnchorTransform(JNIEnv* env, jobject) {
    jfloatArray result = env->NewFloatArray(16);
    if (gSlamEngine) {
        float mat[16];
        gSlamEngine->getAnchorTransform(mat);
        env->SetFloatArrayRegion(result, 0, 16, mat);
    }
    return result;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetPaintingProgress(JNIEnv* env, jobject) {
    if (gSlamEngine) return gSlamEngine->getPaintingProgress();
    return 0.0f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetArScanMode(JNIEnv* env, jobject, jint mode) {
    if (gSlamEngine) gSlamEngine->setArScanMode(mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetMuralMethod(JNIEnv* env, jobject, jint method) {
    if (gSlamEngine) gSlamEngine->setMuralMethod(method);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetPersistentMesh(JNIEnv* env, jobject, jfloatArray vertices, jfloatArray weights) {
    if (!gSlamEngine) return;
    std::vector<float> v, w;
    gSlamEngine->getPersistentMesh(v, w);
    if (!v.empty()) {
        jsize vlen = env->GetArrayLength(vertices);
        jsize wlen = env->GetArrayLength(weights);
        env->SetFloatArrayRegion(vertices, 0, std::min((jsize)v.size(), vlen), v.data());
        env->SetFloatArrayRegion(weights, 0, std::min((jsize)w.size(), wlen), w.data());
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUnrollMesh(JNIEnv* env, jobject, jfloatArray vertices) {
    jsize len = env->GetArrayLength(vertices);
    std::vector<float> v(len);
    env->GetFloatArrayRegion(vertices, 0, len, v.data());

    SurfaceUnroller unroller(32); // Default dim
    auto uv = unroller.unroll(v);

    jfloatArray result = env->NewFloatArray(uv.size() * 2);
    std::vector<float> flatUv;
    for (const auto& p : uv) { flatUv.push_back(p.x); flatUv.push_back(p.y); }
    env->SetFloatArrayRegion(result, 0, flatUv.size(), flatUv.data());
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeExportFingerprint(
        JNIEnv* env, jobject thiz) {
    if (!gSlamEngine) return nullptr;
    std::vector<uint8_t> fingerprint = gSlamEngine->exportFingerprint();
    if (fingerprint.empty()) return nullptr;

    jbyteArray result = env->NewByteArray(fingerprint.size());
    env->SetByteArrayRegion(result, 0, fingerprint.size(), (jbyte*)fingerprint.data());
    return result;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeAlignToFingerprint(
        JNIEnv* env, jobject thiz, jbyteArray data) {
    if (!gSlamEngine) return;
    jsize size = env->GetArrayLength(data);
    jbyte* buffer = env->GetByteArrayElements(data, nullptr);

    gSlamEngine->alignToFingerprint((uint8_t*)buffer, size);

    env->ReleaseByteArrayElements(data, buffer, JNI_ABORT);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hereliesaz_graffitixr_core_collaboration_CollaborationManager_nativeExportFingerprint(
        JNIEnv* env, jobject thiz) {
    std::vector<uint8_t> fingerprint = mobilegs::exportFingerprint();
    jbyteArray result = env->NewByteArray(fingerprint.size());
    env->SetByteArrayRegion(result, 0, fingerprint.size(), (jbyte*)fingerprint.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_core_collaboration_CollaborationManager_nativeAlignToPeer(
        JNIEnv* env, jobject thiz, jbyteArray data) {
    jsize size = env->GetArrayLength(data);
    jbyte* buffer = env->GetByteArrayElements(data, nullptr);
    mobilegs::alignToFingerprint((uint8_t*)buffer, size);
    env->ReleaseByteArrayElements(data, buffer, JNI_ABORT);
}

} // extern "C"
