#include <jni.h>
#include <vector>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>
#include "include/SlamEngine.h"
#include "include/VulkanBackend.h"
#include "include/MobileGS.h"

static VulkanBackend* gVulkanRenderer = nullptr;
static MobileGS* gSlamEngine = nullptr;

extern "C" {

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initialize(JNIEnv* env, jobject thiz) {
    if (!gSlamEngine) gSlamEngine = new MobileGS();
    gSlamEngine->initialize(1920, 1080);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_ensureInitialized(JNIEnv* env, jobject thiz) {
    if (!gSlamEngine) {
        gSlamEngine = new MobileGS();
        gSlamEngine->initialize(1920, 1080);
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroy(JNIEnv* env, jobject thiz) {
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

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_createOnGlThread(JNIEnv* env, jobject thiz) {}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_resetGLState(JNIEnv* env, jobject thiz) {}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_setVisualizationMode(JNIEnv* env, jobject thiz, jint mode) {}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_onSurfaceChanged(JNIEnv* env, jobject thiz, jint width, jint height) {
    if (gSlamEngine) gSlamEngine->initialize(width, height);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_draw(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) gSlamEngine->render();
    if (gVulkanRenderer) gVulkanRenderer->renderFrame();
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_setBitmap(JNIEnv* env, jobject thiz, jobject bitmap) {}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateCamera(JNIEnv* env, jobject thiz, jfloatArray viewMatrix, jfloatArray projMatrix) {
    if (gSlamEngine) {
        jfloat* view = env->GetFloatArrayElements(viewMatrix, nullptr);
        jfloat* proj = env->GetFloatArrayElements(projMatrix, nullptr);
        gSlamEngine->updateCamera(view, proj);
        env->ReleaseFloatArrayElements(viewMatrix, view, JNI_ABORT);
        env->ReleaseFloatArrayElements(projMatrix, proj, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateLight(JNIEnv* env, jobject thiz, jfloat intensity) {}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedMonocularData(JNIEnv* env, jobject thiz, jobject data, jint width, jint height) {}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedStereoData(
        JNIEnv* env, jobject thiz, jbyteArray left, jbyteArray right, jint w, jint h) {
    jbyte* l_ptr = env->GetByteArrayElements(left, nullptr);
    jbyte* r_ptr = env->GetByteArrayElements(right, nullptr);
    SlamEngine::getInstance()->processStereo(reinterpret_cast<int8_t*>(l_ptr), reinterpret_cast<int8_t*>(r_ptr), w, h);
    env->ReleaseByteArrayElements(left, l_ptr, JNI_ABORT);
    env->ReleaseByteArrayElements(right, r_ptr, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedLocationData(JNIEnv* env, jobject thiz, jdouble lat, jdouble lon, jdouble alt) {}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_processTeleologicalFrame(JNIEnv* env, jobject thiz, jobject buffer, jlong timestamp) {}

JNIEXPORT jboolean JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_saveKeyframe(JNIEnv* env, jobject thiz, jlong timestamp) { return JNI_TRUE; }

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_toggleFlashlight(JNIEnv* env, jobject thiz, jboolean enabled) {}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initVulkan(
        JNIEnv* env, jobject thiz, jobject surface, jobject asset_mgr, jint width, jint height) {
    if (!gVulkanRenderer) gVulkanRenderer = new VulkanBackend();
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    AAssetManager* mgr = AAssetManager_fromJava(env, asset_mgr);
    gVulkanRenderer->initialize(window, mgr);
    gVulkanRenderer->resize(width, height);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_resizeVulkan(JNIEnv* env, jobject thiz, jint width, jint height) {
    if (gVulkanRenderer) gVulkanRenderer->resize(width, height);
}

JNIEXPORT void JNICALL Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroyVulkan(JNIEnv* env, jobject thiz) {
    if (gVulkanRenderer) {
        gVulkanRenderer->destroy();
        delete gVulkanRenderer;
        gVulkanRenderer = nullptr;
    }
}

} // extern "C"