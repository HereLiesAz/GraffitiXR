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

#include "VoxelHash.h"
#include "SurfaceMesh.h"
#include "NativeUtil.h"

class MobileGS {
public:
    MobileGS() {}
    ~MobileGS();

    void initialize(int width, int height);
    void initGl();
    void resetGlContext();
    void updateCamera(float* viewMat, float* projMat);
    void updateMappingCamera(float* viewMat, float* projMat);
    void updateLightLevel(float level);
    void updateAnchorTransform(float* transformMat);
    void updateDeviceMotion(float* angularVel, float* linearVel);

    void processDepthFrame(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, const float* intrinsics, bool isYuv, float confidence);
    void pushFrame(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, const float* intrinsics, bool isYuv, float confidence);
    void pushPointCloud(const std::vector<float>& points);

    void setArCoreTrackingState(bool isTracking);
    void restoreWallFingerprint(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d);
    void scheduleRelocCheck(const cv::Mat& colorFrame);
    void getAnchorTransform(float* outMat16) const;
    void setArtworkFingerprint(const cv::Mat& composite, const uint8_t* depthData, int depthW, int depthH, int depthStride, const float* intrinsics4, const float* viewMat16);

    struct FingerprintData {
        std::vector<cv::KeyPoint> keypoints;
        std::vector<float> points3d;
        cv::Mat descriptors;
    };
    FingerprintData generateFingerprint(const cv::Mat& image, const cv::Mat& mask, const uint8_t* depthData, int depthW, int depthH, int depthStride, const float* intrinsics, const float* viewMat);

    bool loadSuperPoint(const std::vector<uchar>& onnxBytes);
    void clearMap();
    void pruneByConfidence(float threshold);
    void setArScanMode(int mode);
    void setMuralMethod(int method);
    void setViewportSize(int width, int height);
    void setRelocEnabled(bool enabled);
    void setVoxelSize(float size);
    void setMappingPaused(bool paused) { mMappingPaused = paused; }

    int getSplatCount() const { return mVoxelHash.getSplatCount(); }
    int getImmutableSplatCount() const { return mVoxelHash.getImmutableSplatCount(); }
    void getConfidenceAvgs(float& outVisible, float& outGlobal) const;
    void getAnchorCandidates(std::vector<Splat>& out, float threshold, int maxCount) const { mVoxelHash.getAnchorCandidates(out, threshold, maxCount); }
    void setSplatsVisible(bool visible) { mSplatsVisible = visible; }
    float getPaintingProgress() const { return mPaintingProgress.load(std::memory_order_relaxed); }

    void saveModel(const std::string& path);
    void loadModel(const std::string& path);
    bool importModel3D(const std::string& path);

    void updatePersistentMesh(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat);
    void getPersistentMesh(std::vector<float>& outVertices, std::vector<float>& outWeights);

    // Collaboration methods
    std::vector<uint8_t> exportFingerprint();
    void alignToFingerprint(const uint8_t* data, size_t size);

    void draw();
    void destroy();
    std::mutex& getMutex() { return mMutex; }

private:
    void mapThreadFunc();
    void optimizeThreadFunc();
    void sortThreadFunc();

    struct FrameData {
        cv::Mat depth;
        cv::Mat color;
        bool isYuv = false;
        float viewMatrix[16];
        float projMatrix[16];
        float intrinsics[4];
        bool hasIntrinsics = false;
        float confidence = 0.5f;
    };

    std::thread mMapThread;
    std::mutex mQueueMutex;
    std::condition_variable mQueueCv;
    std::vector<FrameData> mFrameQueue;
    std::atomic<bool> mMapRunning{false};

    std::thread mOptimizeThread;
    std::atomic<bool> mOptimizeRunning{false};

    std::thread mSortThread;
    std::atomic<bool> mSortRunning{false};

    void relocThreadFunc();
    void runPnPMatch(const cv::Mat& frame);
    void tryUpdateFingerprint(const cv::Mat& color, const cv::Mat& depth, const float* viewMat, const float* projMat);
    void interpolateAnchorStep();

    mutable std::mutex mMutex;
    bool mIsArCoreTracking = false;

    cv::Ptr<cv::ORB> mFeatureDetector;
    cv::Ptr<cv::DescriptorMatcher> mMatcher;    // BruteForce-Hamming for ORB (CV_8U)
    cv::Ptr<cv::DescriptorMatcher> mL2Matcher;  // BruteForce-L2 for SuperPoint (CV_32F)
    SuperPointDetector mSuperPoint;

    cv::Mat mWallDescriptors;
    std::vector<cv::Point3f> mWallKeypoints3D;
    cv::Mat mArtworkDescriptors;
    std::vector<cv::Point3f> mArtworkKeypoints3D;
    std::atomic<float> mPaintingProgress{0.0f};

    VoxelHash   mVoxelHash;
    SurfaceMesh mSurfaceMesh;

    float mAnchorMatrix[16];
    uint64_t mFrameCounter = 0;
    float mLightLevel = 1.0f;
    float mLastAngularVelocity[3] = {0,0,0};
    float mLastLinearVelocity[3] = {0,0,0};

    float mViewMatrix[16];
    float mProjMatrix[16];
    float mMappingViewMatrix[16];
    float mMappingProjMatrix[16];
    bool mCameraReady = false;

    int mScreenWidth = 1920;
    int mScreenHeight = 1080;
    float mVoxelSize = 0.02f;
    std::atomic<bool> mMappingPaused{false};
    bool mSplatsVisible{false};
    int mScanMode = 0; // 0=CLOUD, 1=MURAL
    int mMuralMethod = 0; // 0=VOXEL_HASH, 1=SURFACE_MESH

    std::thread             mRelocThread;
    std::mutex              mRelocMutex;
    std::condition_variable mRelocCv;
    std::atomic<bool>       mRelocRunning{false};
    std::atomic<bool>       mRelocRequested{false};
    std::atomic<bool>       mRelocEnabled{true};
    cv::Mat                 mRelocColorFrame;
};
