#pragma once
#include <opencv2/opencv.hpp>
#include <opencv2/geometry.hpp>
#include <opencv2/calib3d.hpp>
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

#include "NativeUtil.h"

class MobileGS {
public:
    MobileGS() {}
    ~MobileGS();

    void initialize(int width, int height);
    void initGl();
    // Split GL init so the caller can localize a stall to the voxel vs mesh stage on-screen.
    void initVoxelGl();
    void initVoxelGlProgram();
    void initVoxelGlBuffer();
    void initMeshGl();
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
    // Persistent wall *feature* map (lean reloc backbone; docs/RELOC_MAP_DESIGN.md), co-registered to
    // the fingerprint anchor. Restore replaces the stored map (confidence/obs optional). Phase 2a stores
    // it only; reloc matching against it (Phase 2b) is gated separately.
    void restoreWallFeatureMap(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d,
                               const std::vector<float>& confidence, const std::vector<int>& obs,
                               const float* anchorMatrix16, const float* intrinsics4);
    void clearWallFeatureMap();
    int getMapPointCount() const { std::lock_guard<std::mutex> lock(mMutex); return (int)mMapPoints3D.size(); }
    // Phase 2b: gate live map-matching in relocThreadFunc. Default OFF — ships inert until validated.
    void setMapRelocEnabled(bool e) { mMapRelocEnabled.store(e, std::memory_order_relaxed); }
    // Phase 3: passively grow the feature map from reloc-locked frames. Default OFF, and independent of
    // the match flag (accumulate without matching, or match a persisted map without growing).
    void setMapBuildEnabled(bool e) { mMapBuildEnabled.store(e, std::memory_order_relaxed); }
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
    // Canonical fingerprint patch (the marks) the distortion head compares the live crop against.
    // Stored as a raw 256x256 gray (NO CLAHE — the head's frozen SuperPoint was trained on raw gray).
    void setWallPatch(const cv::Mat& img);
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
    void setParallaxMinDegrees(float deg);
    void setMappingPaused(bool paused) { mMappingPaused = paused; }

    int getSplatCount() const { return 0; }            // voxel/splat map deleted
    int getImmutableSplatCount() const { return 0; }   // voxel/splat map deleted
    void getConfidenceAvgs(float& outVisible, float& outGlobal) const;
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

    void draw(bool debugTint = false);
    // Debug perception view: draws the requested representations explicitly, regardless of
    // mMuralMethod or mSplatsVisible. Voxels are confidence-tinted; depth-off so nothing occludes.
    void drawDebugLayers(bool voxels, bool mesh);
    // Voxel-method colour-mask: draws splats as a confidence-graded colour wash over the grayscale camera.
    void drawCoverage();
    void destroy();
    std::mutex& getMutex() { return mMutex; }

private:
    void mapThreadFunc();

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

    void relocThreadFunc();
    // Teleological self-grow (gatekeeper stage): measure how much of the registered artwork base is now
    // corroborated by real wall content in the clean camera frame -> mPaintingProgress. Read-only on the
    // reloc fingerprint; the promotion step (adding validated new marks) is staged separately.
    void tryUpdateFingerprint(const cv::Mat& grayClean);
    // Plane-guided rectification: homography (current-image <-> fingerprint-image) from the wall plane
    // and the VIO baseline between the current and fingerprint-capture views, plus the viewing
    // obliquity in degrees. False if no fingerprint view is stored or the geometry is degenerate.
    bool computeRectifyHomography(const float* viewCur16, cv::Mat& Hcur_fp, cv::Mat& Hfp_cur, double& obliquityDeg);
    // Phase 3 passive builder: on a reloc lock, back-project the frame's features onto the wall plane
    // (fit from the fingerprint points) to get 3D points in the fingerprint frame, associate to the map
    // by descriptor (bump confidence) or add new (capped). Co-registers the map to the fingerprint anchor.
    void growMapFromReloc(const glm::mat4& camFromFp, const std::vector<cv::KeyPoint>& kps,
                          const cv::Mat& descs, double fx, double fy, double cx, double cy);

    mutable std::mutex mMutex;
    std::atomic<bool> mIsArCoreTracking{false};

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

    // --- Persistent wall feature map (lean reloc backbone; docs/RELOC_MAP_DESIGN.md) ---
    // Co-registered to the fingerprint anchor. Phase 2a stores it; reloc matching (Phase 2b) is gated
    // separately, so today this is inert state with no effect on relocalization.
    cv::Mat mMapDescriptors;
    std::vector<cv::Point3f> mMapPoints3D;
    std::vector<float> mMapConfidence;
    std::vector<int> mMapObs;
    float mMapAnchorMatrix[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    float mMapIntrinsics[4] = {0,0,0,0};
    // Phase 2b flag: when true, relocThreadFunc also matches the frustum-gated map and merges those
    // correspondences into PnP. Default OFF so the map has zero effect on reloc until device-validated.
    std::atomic<bool> mMapRelocEnabled{false};
    std::atomic<bool> mMapBuildEnabled{false};

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
    cv::Mat mWallPatch; // raw 256x256 gray canonical patch for the distortion head (desc_fp source)
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
