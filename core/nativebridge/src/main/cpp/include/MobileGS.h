#ifndef GRAFFITIXR_MOBILEGS_H
#define GRAFFITIXR_MOBILEGS_H

#include <string>
#include <vector>
#include <mutex>
#include <opencv2/core.hpp>
#include <android/native_window.h>
#include <android/asset_manager.h>
#include "VulkanBackend.h"

/**
 * MobileGS: The core C++ engine for GraffitiXR.
 * Orchestrates SLAM tracking, Point Cloud management, and Vulkan rendering.
 */
class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    // Lifecycle
    void initialize();
    void reset();
    void onSurfaceChanged(int width, int height);
    void draw();

    // Vulkan Lifecycle
    bool initVulkan(ANativeWindow* window, AAssetManager* mgr);
    void resizeVulkan(int width, int height);
    void destroyVulkan();

    // Camera & Tracking
    void updateCamera(float* viewMatrix, float* projectionMatrix);
    void updateLight(float intensity, float* colorCorrection);
    void alignMap(float* transform);

    // I/O (The missing methods)
    bool saveMap(const char* path);
    bool loadMap(const char* path);
    bool importModel3D(const char* path);
    bool saveKeyframe(const char* path);

    // CV Utils
    void detectEdges(cv::Mat& input, cv::Mat& output);

private:
    // Internal State
    bool isInitialized = false;
    int viewportWidth = 0;
    int viewportHeight = 0;

    // Lighting
    float lightIntensity = 1.0f;
    float lightColor[3] = {1.0f, 1.0f, 1.0f};

    // Rendering Subsystem
    VulkanBackend* vulkanRenderer = nullptr;

    // SLAM Data (Placeholder for sparse point cloud)
    std::vector<cv::Point3f> mapPoints;

    // Matrix storage
    float viewMtx[16];
    float projMtx[16];
    float alignmentMtx[16];
    std::mutex alignMutex;
};

#endif // GRAFFITIXR_MOBILEGS_H