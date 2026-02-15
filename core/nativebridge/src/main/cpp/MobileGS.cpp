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
    // Column-major multiplication: result = a * b
    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            float sum = 0.0f;
            for (int k = 0; k < 4; ++k) {
                // a[row + k*4] * b[k + col*4]
                sum += a[row + k * 4] * b[k + col * 4];
            }
            result[row + col * 4] = sum;
        }
    }
}

static void multiplyMatrices(const float* a, const float* b, float* result) {
    // Column-major multiplication: result = a * b
    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            float sum = 0.0f;
            for (int k = 0; k < 4; ++k) {
                // a[row + k*4] * b[k + col*4]
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
    // FIX(Camera Blocking): Always clear to transparent (0,0,0,0) BEFORE checking initialization.
    // This prevents a black frame from blocking the camera during startup and ensures
    // the AR overlay remains transparent over the CameraX PreviewView.
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    if (!isInitialized) return;

    if (vulkanRenderer) {
        // Pass lighting data to renderer
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
        // Apply alignment: finalView = view * alignmentMtx
        float finalView[16];
        {
            std::lock_guard<std::mutex> lock(alignMutex);
            multiplyMatrices(view, alignmentMtx, finalView);
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
        // LOGI("Map alignment updated."); // Commented out to prevent log spam
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

bool MobileGS::importModel3D(const char* path) {
    LOGI("Importing 3D model from: %s", path);
    // TODO: Implement actual 3D model loading (e.g. GLTF/GLB)
    // For now, we return true to indicate the JNI bridge is working.
    return true;
}

bool MobileGS::saveKeyframe(const char* path) {
    LOGI("Saving keyframe metadata to: %s", path);
    try {
        cv::FileStorage fs(path, cv::FileStorage::WRITE);
        if (!fs.isOpened()) {
            LOGE("Failed to open file for writing: %s", path);
            return false;
        }

        // Write viewport and last known matrices as "Pose Metadata"
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

void MobileGS::detectEdges(cv::Mat& input, cv::Mat& output) {
    if (input.empty()) return;
    cv::Mat gray, blur;
    if (input.channels() == 4) cv::cvtColor(input, gray, cv::COLOR_RGBA2GRAY);
    else if (input.channels() == 3) cv::cvtColor(input, gray, cv::COLOR_RGB2GRAY);
    else gray = input;
    cv::GaussianBlur(gray, blur, cv::Size(5, 5), 1.5);
    cv::Canny(blur, output, 50, 150);
}