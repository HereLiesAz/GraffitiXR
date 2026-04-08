
#pragma once
#include <opencv2/opencv.hpp>
#include "SuperPointDetector.h"
#include <mutex>
#include <vector>
#include <unordered_map>
#include <string>
#include <thread>
#include <atomic>
#include <condition_variable>
#include <GLES3/gl3.h>

// ~~~ RESTORED TO MARCH 9TH STABLE ARCHITECTURE ~~~

struct Splat {
    float x, y, z;          // Position (World Space)
    float r, g, b, a;       // Color
    float confidence;       // Persistence
    float nx, ny, nz;       // Surface Normal
    float radius;           // Splat Scale
};
static_assert(sizeof(Splat) == 48, "Splat struct layout mismatch.");

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
    void resetGlContext();
    void updateCamera(float* viewMat, float* projMat);
    void updateMappingCamera(float* viewMat, float* projMat);
    void updateLightLevel(float level);
    void updateAnchorTransform(float* transformMat);

    // Restoration: World-space processing pipeline
    void processDepthFrame(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, const float* intrinsics, bool isYuv);
    void pushFrame(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, const float* intrinsics, bool isYuv);

    void setArCoreTrackingState(bool isTracking);
    void restoreWallFingerprint(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d);
    void scheduleRelocCheck(const cv::Mat& colorFrame);
    void getAnchorTransform(float* outMat16) const;
    void setArtworkFingerprint(const cv::Mat& composite, const uint8_t* depthData, int depthW, int depthH, int depthStride, const float* intrinsics4, const float* viewMat16);
    bool loadSuperPoint(const std::vector<uchar>& onnxBytes);
    void clearMap();
    void pruneByConfidence(float threshold);
    void setViewportSize(int width, int height);
    void setRelocEnabled(bool enabled);
    void setVoxelSize(float size);

    int getSplatCount() const { return mPointCount; }
    void setSplatsVisible(bool visible) { mSplatsVisible = visible; }
    float getPaintingProgress() const { return mPaintingProgress.load(std::memory_order_relaxed); }

    void saveModel(const std::string& path);
    void loadModel(const std::string& path);
    bool importModel3D(const std::string& path);

    void draw();
    void destroy();
    std::mutex& getMutex() { return mMutex; }

private:
    void pruneMap();
    void continuousOptimize();
    void initShaders();

    std::mutex mMapMutex;
    void mapThreadFunc();
    struct FrameData {
        cv::Mat depth;
        cv::Mat color;
        bool isYuv = false;
        float viewMatrix[16];
        float projMatrix[16];
        float intrinsics[4];
        bool hasIntrinsics = false;
    };
    std::thread mMapThread;
    std::mutex mQueueMutex;
    std::condition_variable mQueueCv;
    std::vector<FrameData> mFrameQueue;
    std::atomic<bool> mMapRunning{false};

    void sortThreadFunc();
    cv::Point3f getCameraWorldPosition() const;
    void interpolateAnchorStep();

    void relocThreadFunc();
    void runPnPMatch(const cv::Mat& frame);
    void tryUpdateFingerprint(const cv::Mat& color, const cv::Mat& depth, const float* viewMat, const float* projMat);

    mutable std::mutex mMutex;
    bool mIsArCoreTracking = false;

    cv::Ptr<cv::ORB> mFeatureDetector;
    cv::Ptr<cv::DescriptorMatcher> mMatcher;
    SuperPointDetector mSuperPoint;

    cv::Mat mWallDescriptors;
    std::vector<cv::Point3f> mWallKeypoints3D;
    cv::Mat mArtworkDescriptors;
    std::vector<cv::Point3f> mArtworkKeypoints3D;
    std::atomic<float> mPaintingProgress{0.0f};

    std::vector<Splat> splatData;
    std::unordered_map<VoxelKey, int, VoxelKeyHash> mVoxelGrid;

    std::thread mSortThread;
    std::mutex mSortMutex;
    std::condition_variable mSortCv;
    std::atomic<bool> mSortRunning{false};
    std::atomic<bool> mSortRequested{false};
    std::vector<uint32_t> mDrawIndices;
    bool mIndicesDirty = false;

    float mTargetAnchorMatrix[16];
    bool  mAnchorInterpolating   = false;
    float mInterpolationProgress = 0.0f;
    static constexpr int   INTERP_FRAMES = 30;
    static constexpr float INTERP_STEP   = 1.0f / 30.0f;

    std::thread             mRelocThread;
    std::mutex              mRelocMutex;
    std::condition_variable mRelocCv;
    std::atomic<bool>       mRelocRunning{false};
    std::atomic<bool>       mRelocRequested{false};
    std::atomic<bool>       mRelocEnabled{true};
    cv::Mat                 mRelocColorFrame;
    uint64_t                mLastRelocTriggerFrame = 0;
    static constexpr uint64_t LOOP_CLOSURE_INTERVAL = 120;
    static constexpr float    DRIFT_THRESHOLD_M     = 0.003f;

    std::atomic<bool>       mFingerprintRequested{false};
    cv::Mat                 mFingerprintColorFrame;
    cv::Mat                 mFingerprintDepthFrame;
    float                   mFingerprintViewMatrix[16];
    float                   mFingerprintProjMatrix[16];

    uint64_t mLastFingerprintUpdateFrame = 0;
    static constexpr uint64_t FINGERPRINT_UPDATE_INTERVAL = 600;
    static constexpr size_t   MAX_FINGERPRINT_KEYPOINTS   = 2000;

    GLuint mProgram = 0;
    GLuint mPointVbo = 0;
    GLuint mIndexVbo = 0;
    std::atomic<int> mPointCount{0};
    bool mSplatsVisible{true};

    GLuint mMeshProgram = 0;
    GLuint mMeshVbo = 0;
    GLuint mMeshIbo = 0;
    std::atomic<int> mMeshIndexCount{0};
    std::vector<float> mMeshVertices;
    std::vector<uint32_t> mMeshIndices;

    std::mutex mGlDataMutex;
    std::vector<Splat> mPendingSplatData;
    std::vector<float> mPendingMeshVertices;
    std::vector<uint32_t> mPendingMeshIndices;
    bool mGlDataDirty = false;

    uint64_t mFrameCounter = 0;

    float mViewMatrix[16];
    float mProjMatrix[16];
    float mMappingViewMatrix[16];
    float mMappingProjMatrix[16];
    float mAnchorMatrix[16];
    bool mCameraReady = false;

    int mScreenWidth = 1920;
    int mScreenHeight = 1080;
    float mVoxelSize = 0.02f; // Restored to 20mm for stability

    static constexpr float MIN_RENDER_CONFIDENCE = 0.1f; // Restored for immediate feedback
};
