#ifndef GRAFFITIXR_MOBILEGS_H
#define GRAFFITIXR_MOBILEGS_H

#include <string>
#include <vector>
#include <opencv2/core.hpp>
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
    void onSurfaceChanged(int width, int height);
    void draw();

    // Camera & Tracking
    void updateCamera(float* viewMatrix, float* projectionMatrix);
    void updateLight(float intensity, float* colorCorrection);

    // I/O (The missing methods)
    bool saveMap(const char* path);
    bool loadMap(const char* path);

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
};

#endif // GRAFFITIXR_MOBILEGS_H