#pragma once

#include <vector>
#include <mutex>
#include <thread>
#include <condition_variable>
#include <atomic>
#include <cstdint>
#include <GLES3/gl3.h>
#include <glm/glm.hpp>
#include <opencv2/core/mat.hpp>
#include <chrono>
#include <unordered_map>

// ... (Struct definitions remain the same) ...
struct SplatGaussian {
    glm::vec3 position;
    glm::vec3 color;
    glm::vec3 scale;
    float opacity;
};

struct Sortable {
    int index;
    float depth;
};

struct VoxelKey {
    int x, y, z;
    bool operator==(const VoxelKey& other) const {
        return x == other.x && y == other.y && z == other.z;
    }
};

struct VoxelHash {
    size_t operator()(const VoxelKey& k) const {
        return std::hash<int>()(k.x) ^ (std::hash<int>()(k.y) << 1) ^ (std::hash<int>()(k.z) << 2);
    }
};

class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    void initialize();
    void updateCamera(const float* viewMtx, const float* projMtx);
    void processDepthFrame(const cv::Mat& depthMap, int width, int height);
    void setBackgroundFrame(const cv::Mat& frame);
    void processImage(const cv::Mat& image, int width, int height, int64_t timestamp);

    void draw();
    bool saveModel(const std::string& path);
    bool loadModel(const std::string& path);
    void clear();
    
    // NEW: Metric for UI
    int getPointCount();

private:
    void compileShaders();
    void sortThreadLoop();
    void pruneMap();

    GLuint mProgram;
    GLuint mVAO, mVBO, mQuadVBO;
    
    // Unused background rendering resources removed for clarity
    
    glm::mat4 mViewMatrix;
    glm::mat4 mProjMatrix;
    glm::mat4 mSortViewMatrix;

    std::vector<SplatGaussian> mRenderGaussians;
    cv::Mat mPendingBgFrame;

    std::vector<Sortable> mSortListFront;
    std::vector<Sortable> mSortListBack;
    std::thread mSortThread;
    std::mutex mSortMutex;
    std::condition_variable mSortCV;

    std::atomic<bool> mSortRunning;
    std::atomic<bool> mStopThread;
    std::atomic<bool> mSortResultReady;
    
    // NEW: Atomic flag to invalidate sort during pruning
    std::atomic<bool> mMapChanged; 

    std::mutex mDataMutex;
    std::mutex mBgMutex;
    std::atomic<bool> mIsInitialized;

    std::chrono::steady_clock::time_point mLastUpdateTime;
    std::unordered_map<VoxelKey, int, VoxelHash> mVoxelGrid;

    const size_t MAX_POINTS = 65536;
    const float VOXEL_SIZE = 0.02f;
};
