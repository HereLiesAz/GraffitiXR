#include <jni.h>
#include <string>
#include <android/log.h>

// Conditional inclusion for ORB_SLAM3
// Define HAS_ORB_SLAM3 in CMakeLists.txt when the real library is available.
#ifdef HAS_ORB_SLAM3
    #include <System.h>
    // Note: ORB_SLAM3::System::SaveAtlas is typically private.
    // This code assumes a version of ORB_SLAM3 where SaveAtlas is public,
    // or that the library has been patched to expose it.
#else
    #include "include/ORB_SLAM3_Mock.h"
#endif

#define LOG_TAG "GraffitiJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global pointer to the SLAM system
ORB_SLAM3::System* SLAM = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_initNative(JNIEnv *env, jobject thiz, jstring vocPath, jstring settingsPath) {
    LOGI("initNative called: Initializing SLAM system");

    const char *vocPathChars = env->GetStringUTFChars(vocPath, nullptr);
    const char *settingsPathChars = env->GetStringUTFChars(settingsPath, nullptr);

    // Initialize SLAM system
    // Using MONOCULAR sensor as default for this example, and disabling viewer
    SLAM = new ORB_SLAM3::System(vocPathChars, settingsPathChars, ORB_SLAM3::System::MONOCULAR, false);

    env->ReleaseStringUTFChars(vocPath, vocPathChars);
    env->ReleaseStringUTFChars(settingsPath, settingsPathChars);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_disposeNative(JNIEnv *env, jobject thiz) {
    LOGI("disposeNative called: Shutting down SLAM system");
    if (SLAM) {
        SLAM->Shutdown();
        delete SLAM;
        SLAM = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_saveMapNative(JNIEnv *env, jobject thiz) {
    LOGI("saveMapNative called: Saving map");
    if (SLAM) {
        // Saving Atlas as Binary File
        // Note: Check ORB_SLAM3 API visibility for SaveAtlas
        SLAM->SaveAtlas(ORB_SLAM3::System::BINARY_FILE);
    } else {
        LOGE("SLAM system is not initialized, cannot save map.");
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_processFrameNative(JNIEnv *env, jobject thiz, jint width, jint height, jbyteArray data, jlong timestamp) {
    // LOGI("processFrameNative called: Processing frame");

    if (SLAM) {
        jbyte *dataBytes = env->GetByteArrayElements(data, nullptr);

        // Create OpenCV matrix from raw data
        // Assuming single channel (grayscale) for monocular SLAM or as pre-processed input
        cv::Mat im(height, width, CV_8UC1, (unsigned char*)dataBytes);

        // Pass frame to SLAM system
        // Timestamp conversion if necessary (e.g. nanoseconds to seconds)
        double t = (double)timestamp / 1000000000.0;
        SLAM->TrackMonocular(im, t);

        env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);
    }
}

} // extern "C"
