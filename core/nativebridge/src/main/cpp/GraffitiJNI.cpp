#include <jni.h>
#include <vector>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>
#include "include/SlamEngine.h"
#include "include/VulkanBackend.h"

static VulkanBackend* gVulkanRenderer = nullptr;

extern "C" {

// Pattern: Java_package_path_ClassName_methodName
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initialize(JNIEnv* env, jobject thiz) {
    SlamEngine::getInstance()->initialize();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_ensureInitialized(JNIEnv* env, jobject thiz) {
    SlamEngine::getInstance()->initialize(); // Ensure logic
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroy(JNIEnv* env, jobject thiz) {
    SlamEngine::getInstance()->destroy();
    if (gVulkanRenderer) {
        gVulkanRenderer->destroy();
        delete gVulkanRenderer;
        gVulkanRenderer = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initVulkan(
    JNIEnv* env, jobject thiz, jobject surface, jobject asset_mgr, jint width, jint height) {

    if (!gVulkanRenderer) gVulkanRenderer = new VulkanBackend();

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    AAssetManager* mgr = AAssetManager_fromJava(env, asset_mgr);

    gVulkanRenderer->initialize(window, mgr);
    gVulkanRenderer->resize(width, height);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_draw(JNIEnv* env, jobject thiz) {
    if (gVulkanRenderer) gVulkanRenderer->renderFrame();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedStereoData(
    JNIEnv* env, jobject thiz, jbyteArray left, jbyteArray right, jint w, jint h) {

    jbyte* l_ptr = env->GetByteArrayElements(left, nullptr);
    jbyte* r_ptr = env->GetByteArrayElements(right, nullptr);

    SlamEngine::getInstance()->processStereo(
        reinterpret_cast<int8_t*>(l_ptr),
        reinterpret_cast<int8_t*>(r_ptr),
        w, h
    );

    env->ReleaseByteArrayElements(left, l_ptr, JNI_ABORT);
    env->ReleaseByteArrayElements(right, r_ptr, JNI_ABORT);
}

} // extern "C"