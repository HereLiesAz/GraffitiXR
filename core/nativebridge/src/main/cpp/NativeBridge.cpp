#include <jni.h>
#include <vector>
#include <android/log.h>
#include "include/MobileGS.h"
#include "include/VulkanBackend.h"

#define LOG_TAG "NativeBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

MobileGS* gEngine = nullptr;
VulkanBackend* gVulkanRenderer = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initVulkanEngine(JNIEnv* env, jobject thiz) {
    if (!gVulkanRenderer) gVulkanRenderer = new VulkanBackend();
    if (!gEngine) gEngine = new MobileGS();
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroyVulkanEngine(JNIEnv* env, jobject thiz) {
    if (gEngine) {
        delete gEngine;
        gEngine = nullptr;
    }
    if (gVulkanRenderer) {
        gVulkanRenderer->destroy();
        delete gVulkanRenderer;
        gVulkanRenderer = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroyVulkan(JNIEnv* env, jobject thiz) {
    Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroyVulkanEngine(env, thiz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_processCameraFrame(JNIEnv* env, jobject thiz, jobject yuv_buffer, jint width, jint height, jint stride) {
    // Standard frame ingestion
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_processDepthFrame(JNIEnv* env, jobject thiz, jobject depth_buffer, jint width, jint height) {
    // Standard depth ingestion
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetPersistedPointBuffer(JNIEnv* env, jobject thiz) {
    if (!gEngine) return nullptr;

    // Engine must return a vector padded to 4 floats per point (x, y, z, 1.0f)
    // to appease ARCore's native FloatBuffer layout expectations.
    const std::vector<float>& points = gEngine->getVoxelMapPointsPadded();
    if (points.empty()) return nullptr;

    // Zero-copy direct buffer pointing to the engine's internal vector memory.
    // GC is oblivious, latency is zero.
    return env->NewDirectByteBuffer((void*)points.data(), points.size() * sizeof(float));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetPersistedPointCount(JNIEnv* env, jobject thiz) {
    if (!gEngine) return 0;
    return gEngine->getVoxelMapPointsPadded().size() / 4;
}