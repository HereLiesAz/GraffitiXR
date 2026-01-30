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

// Render-ready struct (strictly packed for GPU instancing)
struct SplatRenderData {
    glm::vec3 position; // 0, 1, 2
    glm::vec3 color;    // 3, 4, 5
    float scale;        // 6
    float opacity;      // 7
};

// Full internal metadata
struct SplatMetadata {
    SplatRenderData renderData;
    std::chrono::steady_clock::time_point creationTime;
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

    // Returns true on success.
    // Performs a copy under lock, then writes to disk to prevent UI freezes.
    bool saveModel(const std::string& path);
    bool loadModel(const std::string& path);

    // New API: Transform the entire map (rotation/translation)
    // transformMtx: 16-float array (column-major 4x4 matrix)
    void applyTransform(const float* transformMtx);

    void clear();
    int getPointCount();

private:
    void compileShaders();
    void sortThreadLoop();
    void pruneMap();

    GLuint mProgram;
    GLuint mVAO, mVBO, mQuadVBO;
    GLuint mBgProgram;
    GLuint mBgVAO, mBgVBO;
    GLuint mBgTexture;

    glm::mat4 mViewMatrix;
    glm::mat4 mProjMatrix;
    glm::mat4 mSortViewMatrix;

    // Use the metadata struct internally
    std::vector<SplatMetadata> mGaussians;
    // Cache strictly for rendering (copied from mGaussians)
    std::vector<SplatRenderData> mRenderBuffer;
    bool mRenderBufferDirty;

    cv::Mat mPendingBgFrame;

    std::vector<Sortable> mSortListFront;
    std::vector<Sortable> mSortListBack;
    std::thread mSortThread;
    std::mutex mSortMutex;
    std::condition_variable mSortCV;

    std::atomic<bool> mSortRunning;
    std::atomic<bool> mStopThread;
    std::atomic<bool> mSortResultReady;

    std::atomic<bool> mMapChanged;

    std::mutex mDataMutex; // Protects mGaussians and mRenderBuffer
    std::mutex mBgMutex;
    std::atomic<bool> mNewBgAvailable;
    std::atomic<bool> mHasBgData;
    std::atomic<bool> mIsInitialized;
    int64_t mPendingTimestamp;

    int mFrameCount;

    std::chrono::steady_clock::time_point mLastUpdateTime;
    std::unordered_map<VoxelKey, int, VoxelHash> mVoxelGrid;

    const size_t MAX_POINTS = 65536; // Hard cap
    const float VOXEL_SIZE = 0.005f;
};