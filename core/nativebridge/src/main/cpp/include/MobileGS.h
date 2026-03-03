// ~~~ FILE: ./core/nativebridge/src/main/cpp/include/MobileGS.h ~~~
#pragma once
#include <opencv2/opencv.hpp>
#include <mutex>
#include <vector>
#include <unordered_map>
#include <string>
#include <thread>
#include <atomic>
#include <condition_variable>
#include <GLES3/gl3.h>

// Upgraded Splat Struct (48 bytes)
struct Splat {
    float x, y, z;          // Position (12 bytes)
    float r, g, b, a;       // Color (16 bytes)
    float confidence;       // SLAM Confidence (4 bytes)
    float nx, ny, nz;       // Surface Normal (12 bytes)
    float radius;           // Splat Scale (4 bytes)
};
static_assert(sizeof(Splat) == 48, "Splat struct layout changed — update .bin serialization.");

// Voxel spatial hashing keys
struct VoxelKey {
    int x, y, z;
    bool operator==(const VoxelKey& other) const {
        return x == other.x && y == other.y && z == other.z;
    }
};

struct VoxelKeyHash {
    std::size_t operator()(const VoxelKey& k) const {
        return ((std::hash<int>()(k.x) ^ (std::hash<int>()(k.y) << 1)) >> 1) ^ (std::hash<int>()(k.z) << 1);
    }
};

class MobileGS {
public:
    void initialize(int width, int height);
    void initGl();
    void updateCamera(float* viewMat, float* projMat);
    void updateAnchorTransform(float* transformMat);
    void processDepthFrame(const cv::Mat& depth, const cv::Mat& color);

    void setArCoreTrackingState(bool isTracking);
    bool isTracking() const;

    // Teleological SLAM
    void attemptRelocalization(const cv::Mat& colorFrame);
    void setTargetFingerprint(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d);

    // Project Data I/O
    void saveModel(const std::string& path);
    void loadModel(const std::string& path);

    void draw();
    void destroy();
    std::mutex& getMutex() { return mMutex; }

private:
    void pruneMap();
    void continuousOptimize();
    void initShaders();

    // Background Sorter Thread
    void sortThreadFunc();
    cv::Point3f getCameraWorldPosition() const;

    std::mutex mMutex;
    bool mIsArCoreTracking = false;

    cv::Ptr<cv::ORB> mFeatureDetector;
    cv::Ptr<cv::DescriptorMatcher> mMatcher;

    cv::Mat mTargetDescriptors;
    std::vector<cv::Point3f> mTargetKeypoints3D;

    std::vector<Splat> splatData;
    std::unordered_map<VoxelKey, int, VoxelKeyHash> mVoxelGrid;

    // Depth Sorting Concurrency
    std::thread mSortThread;
    std::mutex mSortMutex;
    std::condition_variable mSortCv;
    std::atomic<bool> mSortRunning{false};
    std::atomic<bool> mSortRequested{false};
    std::vector<uint32_t> mDrawIndices;
    bool mIndicesDirty = false;

    // GLES handles
    GLuint mProgram = 0;
    GLuint mPointVbo = 0;
    GLuint mIndexVbo = 0;  // NEW: Element Buffer for sorted rendering

    int mPointCount = 0;

    // Optimization State
    uint64_t mFrameCounter = 0;

    float mViewMatrix[16];
    float mProjMatrix[16];
    float mAnchorMatrix[16];
    bool mCameraReady = false;

    int mScreenWidth = 1920;
    int mScreenHeight = 1080;
};