#pragma once
#include <opencv2/opencv.hpp>
#include "SuperPointDetector.h"
#include "DistortionHead.h"
#include "LowLightEnhancer.h"
#include <mutex>
#include <vector>
#include <unordered_map>
#include <string>
#include <thread>
#include <atomic>
#include <chrono>
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
    // Ingest a fingerprint built from triangulated metric marks (no depth source): also fixes the
    // fingerprint anchor pose and the intrinsics the reloc PnP should use.
    void restoreWallFingerprintMetric(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d,
                                      const float* anchorMatrix16, const float* intrinsics4);
    void scheduleRelocCheck(const cv::Mat& colorFrame);
    void getAnchorTransform(float* outMat16) const;
    void getRelocResult(float* out19) const;       // [0..15]=pnpMat,16=inliers,17=matches,18=seq
    void getFingerprintAnchor(float* out16) const;
    void setArtworkFingerprint(const cv::Mat& composite, const uint8_t* depthData, int depthW, int depthH, int depthStride, const float* intrinsics4, const float* viewMat16);
    // Detect the same features generateFingerprint would (SuperPoint/ORB-1000, masked) and return their
    // 2D positions in image pixels — for a truthful "what anchors the fingerprint" curation overlay.
    void getFingerprintKeypoints(const cv::Mat& image, const cv::Mat& mask, std::vector<cv::Point2f>& out);
    // Teleological self-grow (default OFF — mutates the live reloc fingerprint, so opt-in only).
    void setSelfGrowEnabled(bool e) { mSelfGrowEnabled.store(e, std::memory_order_relaxed); }
    // Live wall-fingerprint size — diagnostic for relocalization health and watching self-grow.
    int getWallKeypointCount() const { std::lock_guard<std::mutex> lock(mMutex); return (int)mWallKeypoints3D.size(); }
    // SuperPoint detect+describe for one image (gray + CLAHE applied inside, matching the reloc path) so
    // the depth-off triangulated fingerprint can be built from SuperPoint (CV_32F) instead of ORB. False
    // if the model isn't loaded or nothing was found.
    bool getSuperPointFeatures(const cv::Mat& image, std::vector<cv::KeyPoint>& kps, cv::Mat& descs);

    struct FingerprintData {
        std::vector<cv::KeyPoint> keypoints;
        std::vector<float> points3d;
        cv::Mat descriptors;
    };
    FingerprintData generateFingerprint(const cv::Mat& image, const cv::Mat& mask, const uint8_t* depthData, int depthW, int depthH, int depthStride, const float* intrinsics, const float* viewMat);

    bool loadSuperPoint(const std::vector<uchar>& onnxBytes);
    bool loadDistortionHead(const std::vector<uchar>& onnxBytes) { return mDistortionHead.load(onnxBytes); }
    bool loadLowLightEnhancer(const std::vector<uchar>& onnxBytes);
    void clearMap();
    void pruneByConfidence(float threshold);
    void setArScanMode(int mode);
    void setMuralMethod(int method);
    void setViewportSize(int width, int height);
    // Eval: fill out[kStageCount] with average ms/stage since last reset, then reset accumulators.
    void getStageTimingsAndReset(float* out);
    void setStageEnabled(int stage, bool enabled);
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
    // Teleological self-grow (gatekeeper stage): measure how much of the registered artwork base is now
    // corroborated by real wall content in the clean camera frame -> mPaintingProgress. Read-only on the
    // reloc fingerprint; the promotion step (adding validated new marks) is staged separately.
    void tryUpdateFingerprint(const cv::Mat& grayClean);
    void interpolateAnchorStep();
    // Plane-guided rectification: homography (current-image <-> fingerprint-image) from the wall plane
    // and the VIO baseline between the current and fingerprint-capture views, plus the viewing
    // obliquity in degrees. False if no fingerprint view is stored or the geometry is degenerate.
    bool computeRectifyHomography(const float* viewCur16, cv::Mat& Hcur_fp, cv::Mat& Hfp_cur, double& obliquityDeg);

    mutable std::mutex mMutex;
    bool mIsArCoreTracking = false;

    cv::Ptr<cv::ORB> mFeatureDetector;
    cv::Ptr<cv::DescriptorMatcher> mMatcher;    // BruteForce-Hamming for ORB (CV_8U)
    cv::Ptr<cv::DescriptorMatcher> mL2Matcher;  // BruteForce-L2 for SuperPoint (CV_32F)
    SuperPointDetector mSuperPoint;
    DistortionHead mDistortionHead;
    LowLightEnhancer mEnhancer;
    static constexpr float kLowLightThreshold = 0.35f;

    cv::Mat mWallDescriptors;
    std::vector<cv::Point3f> mWallKeypoints3D;
    cv::Mat mArtworkDescriptors;
    std::vector<cv::Point3f> mArtworkKeypoints3D;
    std::atomic<float> mPaintingProgress{0.0f};

    VoxelHash   mVoxelHash;
    SurfaceMesh mSurfaceMesh;

    float mAnchorMatrix[16];

    // --- Pose fusion (Sub-project B): reloc result published for Kotlin to compose correctly ---
    float mPnpCamFromFpWorld[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    std::atomic<int> mPnpInlierCount{0};
    std::atomic<int> mPnpMatchCount{0};
    std::atomic<long> mPnpResultSeq{0};
    float mFingerprintAnchorMatrix[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    // fx,fy,cx,cy the wall fingerprint's 3D points were built with; {0,..} => unset (use a default).
    float mFingerprintIntrinsics[4] = {0,0,0,0};
    // VIO view matrix at fingerprint-capture time + flag; used to rectify oblique live views to the
    // fingerprint's frontal frame before matching (perspective-robust matching). False for fingerprints
    // restored without a capture view (rectification is then skipped — plain matching still runs).
    float mFingerprintViewMatrix[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    bool mHasFingerprintView = false;
    // Teleological self-grow: opt-in flag + the last reloc seq we grew from (only grow on a fresh,
    // confident relock so promoted marks are placed with a current pose, never a stale one).
    std::atomic<bool> mSelfGrowEnabled{false};
    long mLastGrowSeq = 0;
    // VIO view snapshot captured alongside the reloc frame, so the rectifying warp matches that frame.
    float mRelocViewMatrix[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
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

    // --- Evaluation instrumentation (Sub-project A) ---
    // Accumulated wall-time per stage and a sample count, for averaging. Indexes match the Kotlin
    // stage contract: 0=voxelUpdate,1=voxelKeyframe,2=surfaceMesh,3=draw,4=pnpReloc.
    static constexpr int kStageCount = 5;
    std::atomic<double> mStageAccumMs[kStageCount] = {};
    std::atomic<uint64_t> mStageSamples[kStageCount] = {};
    // Per-stage A/B enable flags (default on). When off, the stage's work is skipped so cost diffs
    // are clean. Stage 0 (voxelUpdate) is the relocalization backbone and is NOT gateable.
    std::atomic<bool> mStageEnabled[kStageCount] = { true, true, true, true, true };

    std::thread             mRelocThread;
    std::mutex              mRelocMutex;
    std::condition_variable mRelocCv;
    std::atomic<bool>       mRelocRunning{false};
    std::atomic<bool>       mRelocRequested{false};
    std::atomic<bool>       mRelocEnabled{true};
    cv::Mat                 mRelocColorFrame;
};
