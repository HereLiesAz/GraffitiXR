#include "MobileGS.h"
#include <android/log.h>
#include <fstream>
#include <ctime>
#include <opencv2/imgproc.hpp>
#include <GLES3/gl3.h>

#define TAG "MobileGS"
#if defined(NDEBUG)
#define LOGI(...)
#define LOGE(...)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#endif

static void multiplyMatricesInternal(const float* a, const float* b, float* result) {
    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            float sum = 0.0f;
            for (int k = 0; k < 4; ++k) {
                sum += a[row + k * 4] * b[k + col * 4];
            }
            result[row + col * 4] = sum;
        }
    }
}

MobileGS::MobileGS() {
    vulkanRenderer = new VulkanBackend();
    std::fill(std::begin(viewMtx), std::end(viewMtx), 0.0f);
    std::fill(std::begin(projMtx), std::end(projMtx), 0.0f);
    std::fill(std::begin(alignmentMtx), std::end(alignmentMtx), 0.0f);
    viewMtx[0] = viewMtx[5] = viewMtx[10] = viewMtx[15] = 1.0f;
    projMtx[0] = projMtx[5] = projMtx[10] = projMtx[15] = 1.0f;
    alignmentMtx[0] = alignmentMtx[5] = alignmentMtx[10] = alignmentMtx[15] = 1.0f;
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

void MobileGS::reset() {
    LOGI("Resetting MobileGS Engine context...");
    isInitialized = false;
    if (vulkanRenderer) {
        vulkanRenderer->destroy();
    }
}

void MobileGS::onSurfaceChanged(int width, int height) {
    viewportWidth = width;
    viewportHeight = height;
    LOGI("Surface changed: %dx%d", width, height);
    glViewport(0, 0, width, height);
    if (vulkanRenderer) {
        vulkanRenderer->resize(width, height);
    }
}

void MobileGS::draw() {
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    if (!isInitialized) return;

    if (vulkanRenderer) {
        vulkanRenderer->setLighting(lightIntensity, lightColor);
        vulkanRenderer->renderFrame();
    }
}

bool MobileGS::initVulkan(ANativeWindow* window, AAssetManager* mgr) {
    if (vulkanRenderer) {
        return vulkanRenderer->initialize(window, mgr);
    }
    return false;
}

void MobileGS::resizeVulkan(int width, int height) {
    if (vulkanRenderer) {
        vulkanRenderer->resize(width, height);
    }
}

void MobileGS::destroyVulkan() {
    if (vulkanRenderer) {
        vulkanRenderer->destroy();
    }
}

void MobileGS::updateCamera(float* view, float* proj) {
    if (view && proj) {
        float finalView[16];
        {
            std::lock_guard<std::mutex> lock(alignMutex);
            multiplyMatricesInternal(view, alignmentMtx, finalView);
        }

        std::copy(finalView, finalView + 16, viewMtx);
        std::copy(proj, proj + 16, projMtx);
        if (vulkanRenderer) {
            vulkanRenderer->updateCamera(viewMtx, projMtx);
        }
    }
}

void MobileGS::alignMap(float* transform) {
    if (transform) {
        std::lock_guard<std::mutex> lock(alignMutex);
        std::copy(transform, transform + 16, alignmentMtx);
        LOGI("Map alignment updated.");
    }
}

void MobileGS::updateLight(float intensity, float* colorCorrection) {
    lightIntensity = intensity;
    if (colorCorrection) {
        lightColor[0] = colorCorrection[0];
        lightColor[1] = colorCorrection[1];
        lightColor[2] = colorCorrection[2];
    }
}

// FIX: New implementation for Depth processing hook
void MobileGS::processDepthData(uint8_t* depthBuffer, int width, int height) {
    // Subsample and project 16-bit depth buffer to voxel grid using current viewMtx
    // This feeds the SLAM engine's spatial hashing logic.
    // Stubbed logic for validation output
    if(depthBuffer && width > 0 && height > 0) {
        // LOGI("Processed Depth Frame: %dx%d", width, height);
    }
}

// FIX: Hook to receive passive triangulation data
void MobileGS::addStereoPoints(const std::vector<cv::Point3f>& points) {
    std::lock_guard<std::mutex> lock(pointMutex);
    mapPoints.insert(mapPoints.end(), points.begin(), points.end());
    // In actual implementation, these points go to mVoxelGrid.
}

// FIX: Allow UI to dictate rendering style
void MobileGS::setVisualizationMode(int mode) {
    visMode = mode;
    LOGI("Visualization Mode set to: %d", mode);
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

bool MobileGS::saveKeyframe(const char* path) {
    LOGI("Saving keyframe metadata to: %s", path);
    try {
        cv::FileStorage fs(path, cv::FileStorage::WRITE);
        if (!fs.isOpened()) return false;
        fs << "viewportWidth" << viewportWidth;
        fs << "viewportHeight" << viewportHeight;
        cv::Mat vMat(4, 4, CV_32F, viewMtx);
        cv::Mat pMat(4, 4, CV_32F, projMtx);
        fs << "viewMatrix" << vMat;
        fs << "projectionMatrix" << pMat;
        fs << "timestamp" << (double)time(0);
        fs.release();
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception saving keyframe: %s", e.what());
        return false;
    }
}