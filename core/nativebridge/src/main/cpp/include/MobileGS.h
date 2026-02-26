#ifndef GRAFFITIXR_MOBILEGS_H
#define GRAFFITIXR_MOBILEGS_H

#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <opencv2/core.hpp>
#include <android/native_window.h>
#include <android/asset_manager.h>
#include "VulkanBackend.h"

// Voxel hashing structure for Sparse SLAM map
struct VoxelKey {
    int x, y, z;
    bool operator==(const VoxelKey& o) const { return x == o.x && y == o.y && z == o.z; }
};

struct VoxelHash {
    std::size_t operator()(const VoxelKey& k) const {
        return std::hash<int>()(k.x) ^ (std::hash<int>()(k.y) << 1) ^ (std::hash<int>()(k.z) << 2);
    }
};

struct SplatPoint {
    float x, y, z;
    float r, g, b, a;
    float confidence;
};

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

    void processDepthData(uint8_t* depthBuffer, int width, int height);
    void processMonocularData(uint8_t* imageData, int width, int height);
    void addStereoPoints(const std::vector<cv::Point3f>& points);

    // 0 = AR (Vulkan), 1 = Editor (OpenGL)
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

    // Voxel storage system
    std::unordered_map<VoxelKey, SplatPoint, VoxelHash> mVoxelGrid;
    const float VOXEL_SIZE = 0.02f; // 2cm resolution
    const size_t MAX_VOXELS = 4000; // Limit for performance/stability

    float viewMtx[16];
    float projMtx[16];
    float alignmentMtx[16];
    std::mutex alignMutex;
    std::mutex pointMutex;

    // GLES Rendering (Fallback / Editor)
    unsigned int pointProgram = 0;
    unsigned int pointVBO = 0;
};

#endif // GRAFFITIXR_MOBILEGS_H