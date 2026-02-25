#ifndef GRAFFITIXR_MOBILEGS_H
#define GRAFFITIXR_MOBILEGS_H

#include <string>
#include <vector>
#include <mutex>
#include <opencv2/core.hpp>
#include <android/native_window.h>
#include <android/asset_manager.h>
#include "VulkanBackend.h"

class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    void initialize();
    void reset();
    void onSurfaceChanged(int width, int height);
    void draw();

    bool initVulkan(ANativeWindow* window, AAssetManager* mgr);
    void resizeVulkan(int width, int height);
    void destroyVulkan();

    void updateCamera(float* viewMatrix, float* projectionMatrix);
    void updateLight(float intensity, float* colorCorrection);
    void alignMap(float* transform);

    // FIX: Core Engine Function Declarations
    void processDepthData(uint8_t* depthBuffer, int width, int height);
    void addStereoPoints(const std::vector<cv::Point3f>& points);
    void setVisualizationMode(int mode);

    bool saveMap(const char* path);
    bool loadMap(const char* path);
    bool saveKeyframe(const char* path);

private:
    bool isInitialized = false;
    int viewportWidth = 0;
    int viewportHeight = 0;
    int visMode = 0;

    float lightIntensity = 1.0f;
    float lightColor[3] = {1.0f, 1.0f, 1.0f};

    VulkanBackend* vulkanRenderer = nullptr;
    std::vector<cv::Point3f> mapPoints;

    float viewMtx[16];
    float projMtx[16];
    float alignmentMtx[16];
    std::mutex alignMutex;
    std::mutex pointMutex;
};

#endif // GRAFFITIXR_MOBILEGS_H