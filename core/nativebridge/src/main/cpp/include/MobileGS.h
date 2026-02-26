// ~~~ FILE: ./core/nativebridge/src/main/cpp/include/MobileGS.h ~~~
#ifndef GRAFFITIXR_MOBILE_GS_H
#define GRAFFITIXR_MOBILE_GS_H

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <vector>
#include <string>
#include <mutex>
#include <thread>
#include <map>

/**
 * Structure representing a single Gaussian Splat point.
 * Matches Vulkan vertex input expectations.
 */
struct SplatGaussian {
    float pos[3];
    float scale[3];
    float rot[4];
    float color[4];
    float opacity;
    float confidence;
};

/**
 * Key for voxel-based spatial hashing to prevent redundant splat generation.
 */
struct VoxelKey {
    int x, y, z;
    bool operator<(const VoxelKey& other) const {
        if (x != other.x) return x < other.x;
        if (y != other.y) return y < other.y;
        return z < other.z;
    }
};

/**
 * Mobile-optimized Gaussian Splatting engine.
 * Handles real-time point cloud generation from depth data and sorted rendering.
 * Purely mathematical/state-driven. Rendering is delegated to VulkanBackend.
 */
class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    void initialize(int width, int height);
    void init() { initialize(1920, 1080); } // Legacy wrapper
    void updateCamera(const float* viewMatrix, const float* projMatrix);
    void processDepthFrame(const cv::Mat& depthMap, const cv::Mat& colorFrame);

    bool saveModel(const std::string& path);
    bool loadModel(const std::string& path);

    // Thread-safe access for the Vulkan Renderer
    std::vector<SplatGaussian>& getSplats();
    std::mutex& getMutex();

private:
    void sortThreadLoop();

    std::vector<SplatGaussian> mGaussians;
    std::map<VoxelKey, size_t> mVoxelGrid;

    float mViewMatrix[16];
    float mProjMatrix[16];

    std::mutex mDataMutex;
    std::thread mSortThread;
    bool mIsRunning;
    bool mNeedsResort;
};

#endif // GRAFFITIXR_MOBILE_GS_H