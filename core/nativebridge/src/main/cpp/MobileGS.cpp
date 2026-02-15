#include "MobileGS.h"
#include <android/log.h>
#include <fstream>
#include <opencv2/imgproc.hpp>

// Include GLES for the overlay rendering
#include <GLES3/gl3.h>

#define TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

MobileGS::MobileGS() {
    vulkanRenderer = new VulkanBackend();
    std::fill(std::begin(viewMtx), std::end(viewMtx), 0.0f);
    std::fill(std::begin(projMtx), std::end(projMtx), 0.0f);
    viewMtx[0] = viewMtx[5] = viewMtx[10] = viewMtx[15] = 1.0f;
    projMtx[0] = projMtx[5] = projMtx[10] = projMtx[15] = 1.0f;
}

MobileGS::~MobileGS() {
    if (vulkanRenderer) {
        delete vulkanRenderer;
        vulkanRenderer = nullptr;
    }
}

void MobileGS::initialize() {
    LOGI("Initializing MobileGS Engine...");
    isInitialized = true;
}

void MobileGS::onSurfaceChanged(int width, int height) {
    viewportWidth = width;
    viewportHeight = height;
    LOGI("Surface changed: %dx%d", width, height);
    glViewport(0, 0, width, height); // Update GLES viewport

    if (vulkanRenderer) {
        vulkanRenderer->resize(width, height);
    }
}

void MobileGS::draw() {
    if (!isInitialized) return;

    // CRITICAL: Clear the screen to transparent so the CameraX preview shows through!
    // Alpha must be 0.0f
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    // If you have Vulkan content to overlay, handle interop here.
    // For now, we keep the overlay clear.
    if (vulkanRenderer) {
        vulkanRenderer->renderFrame();
    }
}

// ... (Rest of the file remains unchanged: updateCamera, saveMap, loadMap, detectEdges) ...
void MobileGS::updateCamera(float* view, float* proj) {
    if (view && proj) {
        std::copy(view, view + 16, viewMtx);
        std::copy(proj, proj + 16, projMtx);
        if (vulkanRenderer) {
            vulkanRenderer->updateCamera(viewMtx, projMtx);
        }
    }
}

bool MobileGS::saveMap(const char* path) {
    LOGI("Saving world map to: %s", path);
    try {
        cv::FileStorage fs(path, cv::FileStorage::WRITE);
        if (!fs.isOpened()) return false;
        fs << "viewportWidth" << viewportWidth;
        fs << "viewportHeight" << viewportHeight;
        cv::Mat vMat(4, 4, CV_32F, viewMtx);
        cv::Mat pMat(4, 4, CV_32F, projMtx);
        fs << "lastView" << vMat;
        fs << "lastProj" << pMat;
        fs.release();
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception saving map: %s", e.what());
        return false;
    }
}

bool MobileGS::loadMap(const char* path) {
    LOGI("Loading world map from: %s", path);
    try {
        cv::FileStorage fs(path, cv::FileStorage::READ);
        if (!fs.isOpened()) return false;
        int w, h;
        fs["viewportWidth"] >> w;
        fs["viewportHeight"] >> h;
        cv::Mat vMat, pMat;
        fs["lastView"] >> vMat;
        fs["lastProj"] >> pMat;
        if (!vMat.empty() && vMat.total() == 16) {
            std::copy(vMat.ptr<float>(), vMat.ptr<float>() + 16, viewMtx);
        }
        fs.release();
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception loading map: %s", e.what());
        return false;
    }
}

void MobileGS::detectEdges(cv::Mat& input, cv::Mat& output) {
    if (input.empty()) return;
    cv::Mat gray, blur;
    if (input.channels() == 4) cv::cvtColor(input, gray, cv::COLOR_RGBA2GRAY);
    else if (input.channels() == 3) cv::cvtColor(input, gray, cv::COLOR_RGB2GRAY);
    else gray = input;
    cv::GaussianBlur(gray, blur, cv::Size(5, 5), 1.5);
    cv::Canny(blur, output, 50, 150);
}