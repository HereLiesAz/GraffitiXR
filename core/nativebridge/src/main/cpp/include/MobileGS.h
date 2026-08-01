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
    //
    // viewMatrix16 (optional, GL-convention world->camera at capture) enables the plane-guided
    // rectification pass in relocThreadFunc. Without it computeRectifyHomography bails and oblique
    // views get no correction — which was the case for EVERY fingerprint built this way, since only
    // generateFingerprint (the depth path, disabled) ever set it.
    //
    // regions (optional, IMPLEMENTATION.md Phase 2) is one Footprint::Region ordinal per 3D point.
    // The reloc correspondence build excludes INSIDE points — they sit under the artwork and decay
    // as it is painted, so matching against them is what makes accuracy degrade with task progress.
    // EMPTY MEANS ALL BACKBONE, reproducing pre-Phase-2 behaviour exactly for legacy fingerprints.
    void restoreWallFingerprintMetric(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d,
                                      const float* anchorMatrix16, const float* intrinsics4,
                                      const float* viewMatrix16 = nullptr,
                                      const std::vector<uint8_t>& regions = {});
    // Drop the stored wall fingerprint (marks + co-registration). Needed because a fingerprint is
    // otherwise process-lifetime state: the restore calls above only ever REPLACE it, so a project
    // with no fingerprint of its own would keep relocalizing against whatever wall was fingerprinted
    // earlier in the session. Reloc already treats an empty fingerprint as "nothing to match".
    void clearWallFingerprint();
    // Persistent wall *feature* map (lean reloc backbone; docs/RELOC_MAP_DESIGN.md), co-registered to
    // the fingerprint anchor. Restore replaces the stored map (confidence/obs optional). Phase 2a stores
    // it only; reloc matching against it (Phase 2b) is gated separately.
    void restoreWallFeatureMap(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d,
                               const std::vector<float>& confidence, const std::vector<int>& obs,
                               const float* anchorMatrix16, const float* intrinsics4);
    void clearWallFeatureMap();
    int getMapPointCount() const { std::lock_guard<std::mutex> lock(mMutex); return (int)mMapPoints3D.size(); }
    // Phase 3b: pack the live feature map (points/descriptors/confidence/obs + co-registration) into a
    // self-describing little-endian blob for .gxr persistence; empty if there's no map. Race-free (one lock).
    std::vector<uint8_t> exportWallFeatureMap() const;
    // Phase 2b: gate live map-matching in relocThreadFunc. Default OFF — ships inert until validated.
    void setMapRelocEnabled(bool e) { mMapRelocEnabled.store(e, std::memory_order_relaxed); }
    // Phase 3: passively grow the feature map from reloc-locked frames. Default OFF, and independent of
    // the match flag (accumulate without matching, or match a persisted map without growing).
    void setMapBuildEnabled(bool e) { mMapBuildEnabled.store(e, std::memory_order_relaxed); }
    /**
     * Why the last relocalization attempt failed to publish a pose. Ordered by how early the gate
     * sits in the pipeline, so the largest value reached is the furthest the attempt got.
     */
    enum RelocReject {
        kRelocOk = 0,            //!< published a pose
        kRelocNoFingerprint = 1, //!< no wall fingerprint yet (or it carries no 3D points)
        kRelocDisabled = 2,      //!< relocalization switched off
        kRelocNoFeatures = 3,    //!< nothing detected in the live frame, or descriptor type mismatch
        kRelocFewMatches = 4,    //!< fewer than 8 correspondences survived the Lowe ratio test
        kRelocPnpFailed = 5,     //!< solvePnPRansac found no consistent pose
        kRelocFewInliers = 6,    //!< PnP solved but fewer than 6 inliers agreed
    };

    /**
     * Whether a relock is trusted enough to promote new marks into the authoritative fingerprint.
     *
     * This used to be a flat `inliers >= 20`, which could not bootstrap: self-grow is the mechanism
     * that keeps relocalization alive as the original marks get painted over, but a wall whose marks
     * are already half-covered rarely reaches 20 raw inliers, so the fingerprint could only grow when
     * it was already strong — exactly when it needed to least.
     *
     * The ratio is the quality signal (it is what PoseFusion trusts), so a strong ratio now qualifies
     * at a lower absolute count, while a weak ratio still cannot qualify at any count. RANSAC in the
     * reloc PnP remains the geometric backstop for anything this lets through.
     */
    static bool growTrusted(int inliers, int matches) {
        if (inliers >= 20) return true;                  // unchanged: a big lock always qualifies
        if (inliers < 10 || matches <= 0) return false;  // absolute floor — PnP noise below this
        return (float)inliers / (float)matches >= 0.6f;  // above PoseFusion's 0.5 trust bar
    }

    /** Hard ceiling on stored wall marks — a memory guard on the self-grow append. */
    static constexpr size_t kMaxWallMarks = 5000;

    /** Sentinel for "no corroboration attempt has produced a measurement yet". */
    static constexpr float kCorroborationUnmeasured = -1.0f;

    /**
     * Per-attempt decay applied to the CORROBORATION channel when an attempt runs and yields no
     * usable measurement — the wall left the frame, the head refused, the patch is missing. It
     * expresses "my last reading is getting stale", which is a statement about confidence and never
     * was one about how much of the mural is painted.
     */
    static constexpr float kCorroborationDecay = 0.9f;

    /**
     * IMPLEMENTATION.md 4.7 — the two Lowe ratios, named apart so E7 can sweep them independently.
     *
     * They were the same literal `0.75f` written at four call sites, which made them look like one
     * decision. They are not. The reloc match is a GLOBAL search over the backbone with no prior — as
     * are the two map matches, which is why those keep `kRelocLoweRatio` — where a
     * loose ratio admits false correspondences that RANSAC then has to reject; the corroboration
     * match after Phase 4 searches a candidate set roughly 0.2% the size, where the false-positive
     * population has already collapsed and the same threshold throws away true matches for nothing.
     * Phase 4's entire justification is that the constraint lets this one be LOOSENED — so it has to
     * be a separate number, or the effect cannot be measured.
     *
     * Both are pre-registered priors (PARAMETERS.md section 5). `kRelocLoweRatio` is the value that
     * shipped; `kCorrobLoweRatio` is the paper's suggested 0.85, and E7 sets it for real.
     */
    static constexpr float kRelocLoweRatio = 0.75f;
    static constexpr float kCorrobLoweRatio = 0.85f;

    /** Last [RelocReject]. */
    int lastRelocReject() const { return mLastRelocReject.load(std::memory_order_relaxed); }
    /** Correspondences built by the last attempt, successful or not. */
    int lastRelocMatches() const { return mLastRelocMatches.load(std::memory_order_relaxed); }
    /** RANSAC inliers from the last attempt, successful or not (0 if PnP never ran). */
    int lastRelocInliers() const { return mLastRelocInliers.load(std::memory_order_relaxed); }
    /**
     * Features detected in the last live frame, before any matching. Separates "this frame has no
     * texture to work with" from "plenty of texture, none of it the registered wall" — a low match
     * count means opposite things in those two cases.
     */
    int lastRelocDetected() const { return mLastRelocDetected.load(std::memory_order_relaxed); }
    /** Obliquity (deg) measured by the rectification pass, or -1 when it wasn't eligible. */
    int lastRelocObliquityDeg() const { return mLastRelocObliquityDeg.load(std::memory_order_relaxed); }
    /** Extra correspondences the rectification pass contributed to the last attempt. */
    int lastRelocRectifiedCorr() const { return mLastRelocRectifiedCorr.load(std::memory_order_relaxed); }
    /**
     * Stored fingerprint points in F_out — the backbone the reloc PnP is allowed to use — or -1 when
     * no attempt has got far enough to look. An unpartitioned fingerprint reports its full point
     * count, not zero, per the zero-length-regions rule.
     */
    int lastRelocBackboneFeatures() const { return mLastRelocBackboneFeatures.load(std::memory_order_relaxed); }
    /** Correspondences built against F_out, counted apart from the total; -1 when not measured. */
    int lastRelocBackboneMatches() const { return mLastRelocBackboneMatches.load(std::memory_order_relaxed); }
    /** RANSAC inliers that came from F_out points; -1 when PnP never produced an inlier set. */
    int lastRelocBackboneInliers() const { return mLastRelocBackboneInliers.load(std::memory_order_relaxed); }

    /**
     * IMPLEMENTATION.md 4.6 — the corroboration match's own counts, so the effect Phase 4 claims can
     * be seen rather than argued about.
     *
     * `corrobPredicted` is how many design features the current pose puts inside the frame;
     * `corrobMatched` is how many of those the wall answered for. Both -1 until a GATED attempt has
     * run: the global fallback path measures a different thing (every descriptor of the design,
     * framing included) and reporting its numbers here would make the two look comparable.
     *
     * Zero predicted is a real reading — the artist is looking away from the design — and is the
     * state 4.8 exists to keep distinct from zero matched.
     */
    int corrobPredicted() const { return mCorrobPredicted.load(std::memory_order_relaxed); }
    int corrobMatched() const { return mCorrobMatched.load(std::memory_order_relaxed); }
    /** Search radius the last gated attempt used, in pixels, or -1 when no gated attempt has run. */
    float corrobSearchRadiusPx() const { return mCorrobSearchRadiusPx.load(std::memory_order_relaxed); }
    /**
     * Mean reprojection error over the last lock's PnP inliers, in pixels, or -1 when not measured.
     *
     * Feeds the search radius' drift term (SearchRadius.h) and is a diagnostic in its own right.
     * Bounded above by the RANSAC inlier threshold by construction, which is why the gain applied to
     * it is not 1.0 — see `SearchRadius.REPROJ_GAIN`.
     */
    float lastRelocReprojPx() const { return mLastRelocReprojPx.load(std::memory_order_relaxed); }

    /**
     * IMPLEMENTATION.md 4.5 — where the design sits, so the corroboration match can predict where
     * each of its features should appear.
     *
     * @param fpFromDesign16 the design's model matrix expressed in the WALL FINGERPRINT frame,
     *   column-major. This is exactly `FingerprintPartition.partition`'s `designInPointFrame`
     *   (`captureAnchorCam * rigidModelAnchorLocal`) — the same composition Phi is classified with,
     *   deliberately, because a second derivation of the same quantity is a second chance to get the
     *   frame wrong. RIGID: the overlay's scale is folded into the extents, not this matrix.
     * @param halfW,halfH the design's effective (scale-included) half-extents in metres.
     *
     * Pass a null pointer or a non-positive extent to clear the placement, which returns the
     * corroboration match to the global search it did before Phase 4.
     */
    void setDesignPlacement(const float* fpFromDesign16, float halfW, float halfH);

    void scheduleRelocCheck(const cv::Mat& colorFrame);
    /**
     * Cheap pre-check for the three conditions under which scheduleRelocCheck() drops the frame:
     * relocalization disabled, no wall fingerprint to match against yet, or the reloc worker still
     * busy with the previous request.
     *
     * Callers convert a raw camera frame to RGB (and rotate it) before they can schedule it, which
     * is the single most expensive thing on the GL render thread. Ask this first so that work is
     * only paid for on frames the worker will actually take — during scanning there is no
     * fingerprint yet, so every one of those conversions used to be built and thrown away.
     */
    bool relocWantsFrame();
    void getAnchorTransform(float* outMat16) const;
    void getRelocResult(float* out19) const;       // [0..15]=pnpMat,16=inliers,17=matches,18=seq
    void getFingerprintAnchor(float* out16) const;
    void setArtworkFingerprint(const cv::Mat& composite, const uint8_t* depthData, int depthW, int depthH, int depthStride, const float* intrinsics4, const float* viewMat16);
    // Detect the same features generateFingerprint would (SuperPoint/ORB-1000, masked) and return their
    // 2D positions in image pixels — for a truthful "what anchors the fingerprint" curation overlay.
    void getFingerprintKeypoints(const cv::Mat& image, const cv::Mat& mask, std::vector<cv::Point2f>& out);
    // Teleological self-grow (default ON): promotes validated new marks into the live reloc
    // fingerprint so snap-back survives the original reference being painted over. Mutates the
    // authoritative set but is hard-guarded (fresh+confident relock, gatekeeper-validated, plane-fit,
    // re-projection dedup, per-relock + total caps, RANSAC backstop). Toggleable via the UI.
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
    void setViewportSize(int width, int height);
    // Eval: fill out[kStageCount] with average ms/stage since last reset, then reset accumulators.
    void getStageTimingsAndReset(float* out);
    void setStageEnabled(int stage, bool enabled);
    void setRelocEnabled(bool enabled);

    /**
     * EVALUATION.md 3.1 / IMPLEMENTATION.md 6a.4 — fix OpenCV's RNG so a replayed run is
     * reproducible. `solvePnPRansac` draws random samples, so two replays of the same recording can
     * differ and an A/B of two parameter values reports scheduling noise as an effect.
     *
     * A NEGATIVE seed (the default) means "do not touch the RNG", which is the production path:
     * the feature is inert unless an eval run explicitly turns it on. It is an evaluation
     * affordance, not a behaviour change — a fixed seed in production would make every user's
     * RANSAC draw the identical sample sequence forever.
     */
    void setEvalRngSeed(long long seed);
    void setVoxelSize(float size);
    void setParallaxMinDegrees(float deg);
    void setMappingPaused(bool paused) { mMappingPaused = paused; }

    int getSplatCount() const { return 0; }            // voxel/splat map deleted
    int getImmutableSplatCount() const { return 0; }   // voxel/splat map deleted
    void getConfidenceAvgs(float& outVisible, float& outGlobal) const;
    void setSplatsVisible(bool visible) { mSplatsVisible = visible; }
    /**
     * How much of the registered design the wall now answers for: a PROGRESS measurement, on the
     * timescale of hours, roughly monotonic. For the user's progress readout.
     *
     * Never decayed. It used to be, which conflated it with the signal below.
     */
    float getPaintingProgress() const { return mPaintingProgress.load(std::memory_order_relaxed); }

    /**
     * How much the wall corroborates the design RIGHT NOW: a per-attempt CONFIDENCE, on the
     * timescale of frames, moving in both directions. This is what PoseFusion should scale its
     * correction strength by.
     *
     * Split from painting progress because one scalar cannot mean both "the mural is 60% painted"
     * and "I trust this frame". They move on completely different timescales, and the decay that
     * belongs to the second was being applied to the first — so three bad frames read as the mural
     * being un-painted, and the 0.9-per-tick decay made that persist for seconds afterwards.
     *
     * Returns a negative value when no corroboration attempt has produced a measurement yet, which
     * is a different state from "attempted and found nothing" (0.0). Callers that need a plain
     * [0,1] should clamp; PoseFusion's CONF_FLOOR already treats 0 as the conservative floor.
     */
    float getCorroborationConfidence() const {
        return mCorroborationConfidence.load(std::memory_order_relaxed);
    }

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
    // mSplatsVisible. Voxels are confidence-tinted; depth-off so nothing occludes.
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
    /**
     * @param preKps,preDescs features already detected on [grayClean] by the caller. The reloc thread
     *        has just run the detector over this exact frame, and SuperPoint is an ONNX forward pass —
     *        detecting it a second time doubled the per-cycle cost for an identical result. Reused
     *        only when the descriptor type matches the artwork's (a mismatch can't knnMatch anyway,
     *        so it re-detects with the right detector). Pass nullptr to always detect.
     */
    void tryUpdateFingerprint(const cv::Mat& grayClean,
                              const std::vector<cv::KeyPoint>* preKps = nullptr,
                              const cv::Mat* preDescs = nullptr);
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
    // IMPLEMENTATION.md Phase 2 — the footprint partition, parallel to mWallKeypoints3D. One
    // Footprint::Region ordinal per point. EMPTY means "no partition" and is read as all-backbone,
    // so a pre-Phase-2 fingerprint relocalizes exactly as it does on main. Always either empty or
    // the same size as mWallKeypoints3D; the JNI layer drops a mismatched array rather than let a
    // short one be indexed by point index.
    std::vector<uint8_t> mWallRegions;
    // Ordinals must match Footprint.Region's declaration order (INSIDE, BAND, OUTSIDE). Kotlin
    // writes the byte and C++ reads it, so the two enums are a wire format with no compiler to
    // check them; FootprintRegionWireTest pins the Kotlin side against these values.
    static constexpr uint8_t kRegionInside = 0;
    static constexpr uint8_t kRegionBand = 1;
    static constexpr uint8_t kRegionOutside = 2;
    cv::Mat mArtworkDescriptors;
    std::vector<cv::Point3f> mArtworkKeypoints3D;
    // IMPLEMENTATION.md 4.5 — the artwork features' 2D positions in the composite the descriptors
    // were detected on, kept 1:1 with mArtworkDescriptors THROUGH the depth-path row filter in
    // setArtworkFingerprint. mArtworkKeypoints3D is not usable for this: it is empty on the shipping
    // path (the design composite carries no depth) and, when depth does exist, it is expressed in
    // the artwork CAPTURE camera's frame, which has no relation to the wall fingerprint frame the
    // reloc pose is in. Predicting where a design feature should appear goes through the design's
    // placement instead, which is a frame the app actually knows.
    std::vector<cv::Point2f> mArtworkKeypoints2D;
    // Pixel size of that composite; the design-plane mapping is parametric in it, so a design
    // re-registered at a different resolution stays correct without rescaling the keypoints.
    int mArtworkImageW = 0;
    int mArtworkImageH = 0;
    // One flag per artwork descriptor: has the wall EVER answered for this feature? Painting
    // progress is the popcount over this, not the current frame's match count.
    //
    // Before Phase 4 the instantaneous ratio was already the whole design's, so a frame that saw
    // everything reported everything. The gated match only ever looks at the part of the design in
    // view, so the instantaneous ratio would become a statement about where the artist is standing.
    // Accumulating is also closer to what getPaintingProgress() already promises — "on the timescale
    // of hours, roughly monotonic. Never decayed." Cleared whenever a new artwork is registered,
    // because every prior reading was against a different target.
    std::vector<uint8_t> mArtworkCorroborated;
    // Bumped whenever the artwork is replaced or cleared. tryUpdateFingerprint snapshots the
    // descriptors under the lock, spends milliseconds matching outside it, and then merges its
    // result back in; without this it would merge into whatever artwork is registered by then. The
    // sizes alone are not enough — a design swapped for one with the same feature count would pass a
    // length check and corrupt the accumulator with another target's hits.
    long mArtworkGeneration = 0;
    std::atomic<float> mPaintingProgress{0.0f};
    // -1 = never measured, which is NOT the same as 0.0 = measured and found nothing. Zero is a
    // legitimate reading here, so it cannot double as the "no data yet" sentinel.
    std::atomic<float> mCorroborationConfidence{kCorroborationUnmeasured};

    /**
     * Age the corroboration reading after an attempt that produced nothing usable.
     *
     * A never-measured channel stays never-measured: decaying the -1 sentinel would walk it toward
     * zero and quietly turn "no data" into "measured, found nothing" — the exact class of bug that
     * shipped in mLastRelocReject, where 0 doubled as both a valid code and the default.
     */
    void decayCorroboration() {
        const float c = mCorroborationConfidence.load(std::memory_order_relaxed);
        if (c < 0.0f) return;
        mCorroborationConfidence.store(c * kCorroborationDecay, std::memory_order_relaxed);
    }

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
    // IMPLEMENTATION.md 4.5 — the design's rigid pose in the FINGERPRINT frame plus its
    // scale-included half-extents. Guarded by mMutex like everything else here, and false until
    // Kotlin has pushed a placement: absent means the corroboration match falls back to the global
    // search, which is Phase 4 turned off rather than Phase 4 guessing.
    float mDesignFpFromDesign[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    float mDesignHalfW = 0.0f;
    float mDesignHalfH = 0.0f;
    bool mHasDesignPlacement = false;
    // VIO view matrix at fingerprint-capture time + flag; used to rectify oblique live views to the
    // fingerprint's frontal frame before matching (perspective-robust matching). False for fingerprints
    // restored without a capture view (rectification is then skipped — plain matching still runs).
    float mFingerprintViewMatrix[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    bool mHasFingerprintView = false;
    // Teleological self-grow: default OFF + the last reloc seq we grew from (only grow on a fresh,
    // confident relock so promoted marks are placed with a current pose, never a stale one).
    //
    // OFF, not ON. This is the only mechanism in the engine that permanently mutates the
    // authoritative reloc fingerprint, so a bad promotion is not a transient error — it is written
    // into the map and compounds. tryUpdateFingerprint's own comment says it "stays OFF unless
    // explicitly enabled"; the initializer said otherwise and the header won, which meant every
    // release build promoted unsupervised with no user-facing switch to stop it.
    std::atomic<bool> mSelfGrowEnabled{false};

    /** Fixed RANSAC seed for reproducible replay, or <0 for "leave the RNG alone" (default). */
    std::atomic<long long> mEvalRngSeed{-1};
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

    /**
     * Why the most recent relocalization attempt did not publish a pose (a RelocReject value), and
     * the correspondence/inlier counts from that attempt whether or not it succeeded.
     *
     * mPnpMatchCount / mPnpInlierCount only update on SUCCESS, so before the first lock they read
     * zero and a failing pipeline is indistinguishable from an idle one. These always reflect the
     * last attempt, so the diagnostics can say which gate is being missed and by how much.
     */
    // NOT {0}: zero is kRelocOk, so a default-constructed engine would report a successful lock
    // before a single attempt had run and the overlay would read LOCKED on a wall it had never seen.
    // kRelocNoFingerprint is the truthful starting state — there is no fingerprint until one is built.
    std::atomic<int>        mLastRelocReject{kRelocNoFingerprint};
    std::atomic<int>        mLastRelocMatches{0};
    std::atomic<int>        mLastRelocInliers{0};
    std::atomic<int>        mLastRelocDetected{0};
    // Obliquity (degrees) the rectification pass measured, or -1 when it wasn't eligible; and how many
    // extra correspondences it contributed.
    std::atomic<int>        mLastRelocObliquityDeg{-1};
    std::atomic<int>        mLastRelocRectifiedCorr{0};
    // IMPLEMENTATION.md 2.11 — the backbone (F_out) counts, all three -1 for "not measured".
    // They CANNOT default to 0 the way the totals above do. With the partition landed, a zero
    // backbone is a real reading with a specific meaning — the artwork covers the whole wall, so
    // reloc has nothing left to bootstrap from, which is the failure Phase 2's risk section names
    // and ArUiState.backboneTooSmall warns about. If zero also meant "never sampled", the one
    // condition these counters exist to expose would be indistinguishable from an idle thread.
    std::atomic<int>        mLastRelocBackboneFeatures{-1};
    std::atomic<int>        mLastRelocBackboneMatches{-1};
    std::atomic<int>        mLastRelocBackboneInliers{-1};
    // IMPLEMENTATION.md 4.6 — the corroboration match's counts and the radius it used. All -1 for
    // "no gated attempt has run", following the same rule as the trio above: zero predicted is a
    // real reading (the artist is not looking at the design) and cannot double as the default.
    std::atomic<int>        mCorrobPredicted{-1};
    std::atomic<int>        mCorrobMatched{-1};
    std::atomic<float>      mCorrobSearchRadiusPx{-1.0f};
    // Mean reprojection error over the last lock's PnP inliers, in pixels. -1 = not measured, and it
    // stays -1 on every path that does not reach a refined inlier set.
    std::atomic<float>      mLastRelocReprojPx{-1.0f};
    cv::Mat                 mRelocColorFrame;
};
