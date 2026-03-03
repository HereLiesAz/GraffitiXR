// ~~~ FILE: ./core/nativebridge/src/main/cpp/include/MobileGS.h ~~~
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

    // Teleological SLAM
    void setTargetFingerprint(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d);
    void scheduleRelocCheck(const cv::Mat& colorFrame);

    // Fix 4: AI feature matching — load SuperPoint ONNX from bytes (AssetManager buffer).
    // Returns false if model cannot be parsed; ORB fallback stays active.
    bool loadSuperPoint(const std::vector<uchar>& onnxBytes);

    // Lifecycle helpers
    void clearMap();                         // Reset all SLAM state (safe from any thread, no GL)
    void setViewportSize(int width, int height); // Lightweight; does not reinitialize the engine
    void setRelocEnabled(bool enabled);      // Pause/resume background reloc thread

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

    // Fix 1: Smooth anchor interpolation (caller must hold mMutex)
    void interpolateAnchorStep();

    // Fix 2: Background relocalization thread
    void relocThreadFunc();
    void runPnPMatch(const cv::Mat& frame);

    // Fix 3: Dynamic fingerprint accumulation (caller must hold mMutex)
    void tryUpdateFingerprint(const cv::Mat& color);

    std::mutex mMutex;
    bool mIsArCoreTracking = false;

    cv::Ptr<cv::ORB> mFeatureDetector;
    cv::Ptr<cv::DescriptorMatcher> mMatcher;

    // Fix 4: SuperPoint neural detector (OpenCV DNN / ONNX).
    // isLoaded() == false until loadSuperPoint() succeeds; ORB is the fallback.
    SuperPointDetector mSuperPoint;

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

    // Fix 1: Smooth anchor interpolation state
    float mTargetAnchorMatrix[16];
    bool  mAnchorInterpolating   = false;
    float mInterpolationProgress = 0.0f;
    static constexpr int   INTERP_FRAMES = 30;
    static constexpr float INTERP_STEP   = 1.0f / 30.0f;

    // Background relocalization thread (loop closure)
    std::thread             mRelocThread;
    std::mutex              mRelocMutex;
    std::condition_variable mRelocCv;
    std::atomic<bool>       mRelocRunning{false};
    std::atomic<bool>       mRelocRequested{false};
    std::atomic<bool>       mRelocEnabled{true};  // Issue 3: gate for non-AR modes
    cv::Mat                 mRelocColorFrame;
    uint64_t                mLastRelocTriggerFrame = 0;
    static constexpr uint64_t LOOP_CLOSURE_INTERVAL = 60;   // ~1 s at 60 fps
    static constexpr float    DRIFT_THRESHOLD_M     = 0.003f; // 3 mm

    // Fix 3: Dynamic fingerprint accumulation
    cv::Mat  mLastDepthFrame;
    uint64_t mLastFingerprintUpdateFrame = 0;
    static constexpr uint64_t FINGERPRINT_UPDATE_INTERVAL = 300; // ~5 s at 60 fps
    static constexpr size_t   MAX_FINGERPRINT_KEYPOINTS   = 2000;

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