#ifndef GRAFFITIXR_MOBILEGS_H
#define GRAFFITIXR_MOBILEGS_H

#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <cstdint>
#include <opencv2/core.hpp>
#include <android/native_window.h>
#include <android/asset_manager.h>
#include "VulkanBackend.h"

/**
 * Voxel hashing structure for Sparse SLAM map.
 */
struct VoxelKey {
    int x, y, z;
    bool operator==(const VoxelKey& o) const {
        return x == o.x && y == o.y && z == o.z;
    }
};

/**
 * Hash function for VoxelKey to enable use in unordered_map.
 */
struct VoxelHash {
    std::size_t operator()(const VoxelKey& k) const {
        return std::hash<int>()(k.x) ^ (std::hash<int>()(k.y) << 1) ^ (std::hash<int>()(k.z) << 2);
    }
};

/**
 * Data structure for a single 3D Gaussian Splat point.
 * Aligned to 32 bytes for performance.
 */
struct SplatPoint {
    float x, y, z;
    float r, g, b, a;
    float confidence;
};

/**
 * MobileGS: The primary native engine for 3D Gaussian Splatting and Voxel Mapping.
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

    // Vulkan Integration
    bool initVulkan(ANativeWindow* window, AAssetManager* mgr);
    void resizeVulkan(int width, int height);
    void destroyVulkan();

    // State Updates
    void updateCamera(float* viewMatrix, float* projectionMatrix);
    void updateLight(float intensity, float* colorCorrection = nullptr);
    void alignMap(float* transform);

    // Data Ingestion
    void processDepthData(uint8_t* depthBuffer, int width, int height);
    void processMonocularData(uint8_t* imageData, int width, int height);
    void addStereoPoints(const std::vector<cv::Point3f>& points);

    /**
     * Sets the rendering mode.
     * 0 = AR Mode (Vulkan Backend)
     * 1 = Editor Mode (GLES3 Fallback)
     */
    void setVisualizationMode(int mode);

    // Serialization
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
    const float VOXEL_SIZE = 0.05f;
    const size_t MAX_VOXELS = 10000;

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