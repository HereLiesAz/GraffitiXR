#include <jni.h>
#include <string>
#include <vector>
#include <opencv2/core.hpp>
#include "include/MobileGS.h"

static MobileGS* gMobileGS = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_initNative(JNIEnv *env, jobject thiz) {
if (!gMobileGS) {
gMobileGS = new MobileGS();
}
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_destroyNative(JNIEnv *env, jobject thiz) {
if (gMobileGS) {
delete gMobileGS;
gMobileGS = nullptr;
}
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

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_feedSensors(JNIEnv *env, jobject thiz,
        jbyteArray image_data,
jint width, jint height,
jfloatArray point_cloud,
        jint point_count) {
if (!gMobileGS) return;
// 1. Process Background Image (assuming RGBA from Bitmap)
jbyte* pixels = env->GetByteArrayElements(image_data, nullptr);
if (pixels != nullptr && width > 0 && height > 0) {
cv::Mat frame(height, width, CV_8UC4, (unsigned char*)pixels);
gMobileGS->setBackgroundFrame(frame);
}
env->ReleaseByteArrayElements(image_data, pixels, 0);

// 2. Process Sparse Points (Fallback/Augmentation)
// For now we trust feedDepth more for dense mapping, so we skip sparse point ingestion
// unless you want mixed mode. Let's skip for cleaner code, as feedDepth handles geometry.
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
bool result = gMobileGS->saveModel(nativePath);
env->ReleaseStringUTFChars(path, nativePath);
return result;
}

JNIEXPORT jboolean JNICALL
        Java_com_hereliesaz_graffitixr_slam_SlamManager_loadWorld(JNIEnv *env, jobject thiz, jstring path) {
if (!gMobileGS) return false;
const char *nativePath = env->GetStringUTFChars(path, 0);
bool result = gMobileGS->loadModel(nativePath);
env->ReleaseStringUTFChars(path, nativePath);
return result;
}

}