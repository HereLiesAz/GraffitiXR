#include <jni.h>
#include <android/log.h>
#include <VuforiaEngine/VuforiaEngine.h>

static JavaVM* g_javaVM = nullptr;
static jobject g_activity = nullptr;

#define LOG_TAG "GraffitiJNI_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_javaVM = vm;
    LOGI("JNI_OnLoad called, JavaVM stored.");
    return JNI_VERSION_1_6;
}

// --- Configuration ---

extern "C" JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_configSetCreate(JNIEnv *env, jobject thiz) {
    VuEngineConfigSet* configSet;
    if (vuEngineConfigSetCreate(&configSet) != VU_SUCCESS) {
        LOGE("Failed to create config set");
        return 0;
    }
    LOGI("configSetCreate successful");
    return reinterpret_cast<jlong>(configSet);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_configSetDestroy(JNIEnv *env, jobject thiz, jlong config_set_handle) {
    auto* configSet = reinterpret_cast<VuEngineConfigSet*>(config_set_handle);
    vuEngineConfigSetDestroy(configSet);
    LOGI("configSetDestroy successful");
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_configSetAddPlatformAndroidConfig(JNIEnv *env, jobject thiz, jlong config_set_handle, jobject activity) {
    if (g_activity != nullptr) {
        env->DeleteGlobalRef(g_activity);
    }
    g_activity = env->NewGlobalRef(activity);

    VuPlatformAndroidConfig platformConfig = vuPlatformAndroidConfigDefault();
    platformConfig.javaVM = g_javaVM;
    platformConfig.activity = g_activity;

    auto* configSet = reinterpret_cast<VuEngineConfigSet*>(config_set_handle);
    if (vuEngineConfigSetAddPlatformAndroidConfig(configSet, &platformConfig) != VU_SUCCESS) {
        LOGE("Failed to add platform android config");
    } else {
        LOGI("configSetAddPlatformAndroidConfig successful");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_configSetAddLicenseConfig(JNIEnv *env, jobject thiz, jlong config_set_handle, jstring license_key) {
    const char* licenseKeyChars = env->GetStringUTFChars(license_key, nullptr);

    VuLicenseConfig licenseConfig = vuLicenseConfigDefault();
    licenseConfig.key = licenseKeyChars;

    auto* configSet = reinterpret_cast<VuEngineConfigSet*>(config_set_handle);
    if (vuEngineConfigSetAddLicenseConfig(configSet, &licenseConfig) != VU_SUCCESS) {
        LOGE("Failed to add license config");
    } else {
        LOGI("configSetAddLicenseConfig successful");
    }

    env->ReleaseStringUTFChars(license_key, licenseKeyChars);
}


// --- Engine Lifecycle ---

extern "C" JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_engineCreate(JNIEnv *env, jobject thiz, jlong config_set_handle) {
    VuEngine* engine;
    auto* configSet = reinterpret_cast<VuEngineConfigSet*>(config_set_handle);
    VuErrorCode error = 0;
    if (vuEngineCreate(&engine, configSet, &error) != VU_SUCCESS) {
        LOGE("Failed to create Vuforia engine. Error code: %d", error);
        return 0;
    }
    LOGI("engineCreate successful");
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_engineDestroy(JNIEnv *env, jobject thiz, jlong engine_handle) {
    auto* engine = reinterpret_cast<VuEngine*>(engine_handle);
    vuEngineDestroy(engine);
    if (g_activity != nullptr) {
        env->DeleteGlobalRef(g_activity);
        g_activity = nullptr;
    }
    LOGI("engineDestroy successful");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_engineStart(JNIEnv *env, jobject thiz, jlong engine_handle) {
    auto* engine = reinterpret_cast<VuEngine*>(engine_handle);
    if (vuEngineStart(engine) != VU_SUCCESS) {
        LOGE("Failed to start engine");
        return false;
    }
    LOGI("engineStart successful");
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_engineStop(JNIEnv *env, jobject thiz, jlong engine_handle) {
    auto* engine = reinterpret_cast<VuEngine*>(engine_handle);
    if (vuEngineStop(engine) != VU_SUCCESS) {
        LOGE("Failed to stop engine");
        return false;
    }
    LOGI("engineStop successful");
    return true;
}


// --- Rendering ---

extern "C" JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_initRendering(JNIEnv *env, jobject thiz) {
    // This is where you would initialize your rendering pipeline (e.g. shaders, textures).
    // For this implementation, we will rely on Vuforia's built-in rendering.
    LOGI("initRendering called");
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_configureRendering(JNIEnv *env, jobject thiz, jint width, jint height, jint orientation) {
    LOGI("configureRendering called with width: %d, height: %d", width, height);
    // This function is a placeholder for now. A real implementation would need to get the engine handle.
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_renderFrame(JNIEnv *env, jobject thiz, jlong engine_handle) {
    auto* engine = reinterpret_cast<VuEngine*>(engine_handle);
    if (engine == nullptr || !vuEngineIsRunning(engine)) {
        return false;
    }

    VuState* state = nullptr;
    if (vuEngineAcquireLatestState(engine, &state) != VU_SUCCESS) {
        LOGE("Could not acquire latest state for rendering");
        return false;
    }

    VuRenderState renderState;
    if (vuStateGetRenderState(state, &renderState) != VU_SUCCESS) {
        LOGE("Could not get render state");
        vuStateRelease(state);
        return false;
    }

    // This is where you would use the renderState (matrices, viewport, mesh) to draw the scene with OpenGL.
    // For now, we are just logging that we have the data.
    LOGI("renderFrame: Acquired render state.");

    vuStateRelease(state);
    return true;
}


// --- Target Management ---

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_createImageTarget(JNIEnv *env, jobject thiz, jlong engine_handle) {
    LOGI("createImageTarget called");
    auto* engine = reinterpret_cast<VuEngine*>(engine_handle);
    if (engine == nullptr || !vuEngineIsRunning(engine)) {
        LOGE("Engine not running, cannot create image target.");
        return false;
    }

    VuState* state = nullptr;
    if (vuEngineAcquireLatestState(engine, &state) != VU_SUCCESS) {
        LOGE("Could not acquire latest state");
        return false;
    }

    VuCameraFrame* frame = nullptr;
    if (vuStateGetCameraFrame(state, &frame) != VU_SUCCESS) {
        LOGE("Could not get camera frame");
        vuStateRelease(state);
        return false;
    }

    VuImageList* imageList = nullptr;
    vuImageListCreate(&imageList);
    if (vuCameraFrameGetImages(frame, imageList) != VU_SUCCESS) {
        LOGE("Could not get images from frame");
        vuImageListDestroy(imageList);
        vuStateRelease(state);
        return false;
    }

    VuImage* image = nullptr;
    vuImageListGetElement(imageList, 0, &image); // Use the first available image
    if (image == nullptr) {
        LOGE("Could not get image from image list");
        vuImageListDestroy(imageList);
        vuStateRelease(state);
        return false;
    }

    VuImageTargetBufferConfig config = vuImageTargetBufferConfigDefault();
    config.pixelBuffer = vuImageGetPixels(image);
    config.bufferFormat = vuImageGetPixelFormat(image);
    VuVector2I size;
    vuImageGetSize(image, &size);
    config.bufferSize = size;
    config.targetName = "runtime_target";
    config.targetWidth = 0.1f; // 10cm, should be configurable in a real app

    VuObserver* observer = nullptr;
    VuImageTargetBufferCreationError error;
    if (vuEngineCreateImageTargetObserverFromBufferConfig(engine, &observer, &config, &error) != VU_SUCCESS) {
        LOGE("Failed to create image target observer from buffer. Error: %d", error);
        vuImageListDestroy(imageList);
        vuStateRelease(state);
        return false;
    }

    LOGI("Successfully created image target observer.");

    vuImageListDestroy(imageList);
    vuStateRelease(state);

    return true;
}


// --- Other stubs ---

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_setOverlayTexture(JNIEnv *env, jobject thiz, jint width, jint height, jobject pixels) {
    LOGI("setOverlayTexture: STUB");
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_VuforiaJNI_setTextures(JNIEnv *env, jobject thiz, jobjectArray textures) {
    LOGI("setTextures: STUB");
}
