#include <jni.h>
#include <string>
#include <vector>
#include <opencv2/core.hpp>
#include "include/MobileGS.h"

#ifdef HAS_ORB_SLAM3
#include "System.h"
// Global pointer for ORB_SLAM3 System
static ORB_SLAM3::System* SLAM = nullptr;
#endif

// The singleton instance
static MobileGS* gMobileGS = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_initNative(JNIEnv *env, jobject thiz) {
        if (!gMobileGS) {
                gMobileGS = new MobileGS();
                gMobileGS->initialize();
        }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_initSLAM(JNIEnv *env, jobject thiz, jstring vocabPath, jstring settingsPath) {
#ifdef HAS_ORB_SLAM3
        if (!SLAM) {
             const char *nativeVocabPath = env->GetStringUTFChars(vocabPath, 0);
             const char *nativeSettingsPath = env->GetStringUTFChars(settingsPath, 0);

             SLAM = new ORB_SLAM3::System(std::string(nativeVocabPath), std::string(nativeSettingsPath), ORB_SLAM3::System::MONOCULAR, true);

             env->ReleaseStringUTFChars(vocabPath, nativeVocabPath);
             env->ReleaseStringUTFChars(settingsPath, nativeSettingsPath);
        }
#endif
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_destroyNative(JNIEnv *env, jobject thiz) {
        if (gMobileGS) {
                delete gMobileGS;
                gMobileGS = nullptr;
        }
#ifdef HAS_ORB_SLAM3
        if (SLAM) {
            SLAM->Shutdown();
            delete SLAM;
            SLAM = nullptr;
        }
#endif
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_updateCamera(JNIEnv *env, jobject thiz,
        jfloatArray view_mtx,
        jfloatArray proj_mtx) {
        if (!gMobileGS) return;

        jfloat* view = env->GetFloatArrayElements(view_mtx, nullptr);
        jfloat* proj = env->GetFloatArrayElements(proj_mtx, nullptr);

        gMobileGS->updateCamera(view, proj);

        env->ReleaseFloatArrayElements(view_mtx, view, 0);
        env->ReleaseFloatArrayElements(proj_mtx, proj, 0);
}

// RENAMED: feedSensors -> feedImage
// We no longer accept sparse points here. We only care about the color buffer.
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_feedImage(JNIEnv *env, jobject thiz,
        jbyteArray image_data,
        jint width, jint height) {
        if (!gMobileGS) return;

        jbyte* pixels = env->GetByteArrayElements(image_data, nullptr);
        if (pixels != nullptr && width > 0 && height > 0) {
                // Assuming RGBA (CV_8UC4) from Android Bitmap or YUV converter
                cv::Mat frame(height, width, CV_8UC4, (unsigned char*)pixels);
                gMobileGS->setBackgroundFrame(frame);
        }
        env->ReleaseByteArrayElements(image_data, pixels, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_processFrameNative(JNIEnv *env, jobject thiz, jint width, jint height, jbyteArray data, jlong timestamp) {
        jsize len = env->GetArrayLength(data);
        if (len < width * height) return; // Basic size check

        jbyte* pixels = env->GetByteArrayElements(data, nullptr);
        if (pixels != nullptr && width > 0 && height > 0) {
            cv::Mat frame;
            if (len == width * height * 4) {
                 frame = cv::Mat(height, width, CV_8UC4, (unsigned char*)pixels);
            } else if (len == width * height * 3) {
                 frame = cv::Mat(height, width, CV_8UC3, (unsigned char*)pixels);
            } else if (len == width * height) {
                 frame = cv::Mat(height, width, CV_8UC1, (unsigned char*)pixels);
            } else if (len >= width * height * 3 / 2) {
                 // Likely YUV (NV21 or YV12), wrap as single channel full size + half height
                 // ORB_SLAM3 might handle it if we convert, but for now we just wrap the Y plane
                 // which is the first width*height bytes.
                 frame = cv::Mat(height, width, CV_8UC1, (unsigned char*)pixels);
            } else {
                 // Unknown format, do not process
                 env->ReleaseByteArrayElements(data, pixels, 0);
                 return;
            }

#ifdef HAS_ORB_SLAM3
            if (SLAM && !frame.empty()) {
                    // ORB_SLAM3 usually expects timestamp in seconds
                    double t = (double)timestamp / 1e9;
                    SLAM->TrackMonocular(frame, t);
            }
#endif
        }
        env->ReleaseByteArrayElements(data, pixels, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_feedDepth(JNIEnv *env, jobject thiz,
        jbyteArray depth_data,
        jint width, jint height) {
        if (!gMobileGS) return;

        jbyte* data = env->GetByteArrayElements(depth_data, nullptr);
        if (data != nullptr) {
                // ARCore Depth is 16-bit unsigned (CV_16U)
                cv::Mat depthMap(height, width, CV_16U, (unsigned char*)data);
                gMobileGS->processDepthFrame(depthMap, width, height);
        }
        env->ReleaseByteArrayElements(depth_data, data, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_drawFrame(JNIEnv *env, jobject thiz) {
        if (gMobileGS) {
                gMobileGS->draw();
        }
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_saveWorld(JNIEnv *env, jobject thiz, jstring path) {
        if (!gMobileGS) return false;
        const char *nativePath = env->GetStringUTFChars(path, 0);
        bool result = gMobileGS->saveModel(std::string(nativePath));
        env->ReleaseStringUTFChars(path, nativePath);
        return result;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_loadWorld(JNIEnv *env, jobject thiz, jstring path) {
        if (!gMobileGS) return false;
        const char *nativePath = env->GetStringUTFChars(path, 0);
        bool result = gMobileGS->loadModel(std::string(nativePath));
        env->ReleaseStringUTFChars(path, nativePath);
        return result;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_clearMap(JNIEnv *env, jobject thiz) {
        if (gMobileGS) {
                gMobileGS->clear();
        }
}

}