#include "include/MobileGS.h"
#include "include/KeypointGrid.h"
#include "include/SearchRadius.h"
#include <jni.h>
#include <EGL/egl.h>
#include <algorithm>
#include <android/log.h>
#include <cfloat>
#include <cstring>
#include <vector>
#include <fstream>
#include <cmath>
#include <numeric>
#include <sys/resource.h>
#include <glm/glm.hpp>
#include <glm/gtc/matrix_transform.hpp>
#include <glm/gtc/type_ptr.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MobileGS", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MobileGS", __VA_ARGS__)

namespace {
// Normalize illumination so feature matching survives light/color changes: CLAHE on the luma. MUST be
// applied identically to BOTH fingerprint build and live reloc detection or descriptors stop matching.
// thread_local so the reloc/map/UI threads each keep their own reusable instance.
inline void normalizeForFeatures(cv::Mat& gray) {
    if (gray.empty() || gray.type() != CV_8UC1) return;
    static thread_local cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(2.0, cv::Size(8, 8));
    cv::Mat tmp;
    clahe->apply(gray, tmp);   // not in-place, and never aliases a caller's source bitmap
    gray = tmp;
}

// C++17-compatible atomic double add via CAS loop.
inline void atomicAddDouble(std::atomic<double>* a, double v) {
    double old = a->load(std::memory_order_relaxed);
    while (!a->compare_exchange_weak(old, old + v, std::memory_order_relaxed, std::memory_order_relaxed)) {}
}

struct StageTimer {
    std::atomic<double>* accum;
    std::atomic<uint64_t>* count;
    std::chrono::steady_clock::time_point start;
    StageTimer(std::atomic<double>* a, std::atomic<uint64_t>* c)
        : accum(a), count(c), start(std::chrono::steady_clock::now()) {}
    ~StageTimer() {
        double ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - start).count();
        atomicAddDouble(accum, ms);
        count->fetch_add(1, std::memory_order_relaxed);
    }
};
}

std::string gLastSplatTrace = "";

extern JavaVM* gJvm;

struct JniThreadAttacher {
    JNIEnv* env = nullptr;
    bool didAttach = false;
    JniThreadAttacher() {
        if (gJvm) {
            jint res = gJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
            if (res == JNI_EDETACHED) {
                if (gJvm->AttachCurrentThread(&env, nullptr) == JNI_OK) didAttach = true;
            }
        }
    }
    ~JniThreadAttacher() {
        if (didAttach && gJvm) gJvm->DetachCurrentThread();
    }
};

MobileGS::~MobileGS() {
    destroy();
}

void MobileGS::initialize(int width, int height) {
    std::lock_guard<std::mutex> lock(mMutex);
    mScreenWidth = width;
    mScreenHeight = height;
    // 1500, not 500. This is the QUERY side: the live frame matched against a fingerprint built with
    // ORB(1500) (MetricFingerprintBuilder) or ORB(1000) (generateFingerprint). Detecting a third as
    // many features on the query as exist in the reference throws away matches before the ratio test
    // even runs — and PnP needs 8 survivors from a frame that may show the marks small, partial or
    // off-centre. ORB is cheap next to the SuperPoint path this falls back from, and it runs on the
    // background reloc thread, so the symmetric budget is the right default.
    mFeatureDetector = cv::ORB::create(1500);
    mMatcher = cv::DescriptorMatcher::create("BruteForce-Hamming");
    mL2Matcher = cv::DescriptorMatcher::create("BruteForce");

    memset(mViewMatrix, 0, sizeof(mViewMatrix));
    memset(mProjMatrix, 0, sizeof(mProjMatrix));
    memset(mMappingViewMatrix, 0, sizeof(mMappingViewMatrix));
    memset(mMappingProjMatrix, 0, sizeof(mMappingProjMatrix));
    memset(mAnchorMatrix, 0, sizeof(mAnchorMatrix));
    mViewMatrix[0] = mViewMatrix[5] = mViewMatrix[10] = mViewMatrix[15] = 1.0f;
    mProjMatrix[0] = mProjMatrix[5] = mProjMatrix[10] = mProjMatrix[15] = 1.0f;
    mMappingViewMatrix[0] = mMappingViewMatrix[5] = mMappingViewMatrix[10] = mMappingViewMatrix[15] = 1.0f;
    mMappingProjMatrix[0] = mMappingProjMatrix[5] = mMappingProjMatrix[10] = mMappingProjMatrix[15] = 1.0f;
    mAnchorMatrix[0] = mAnchorMatrix[5] = mAnchorMatrix[10] = mAnchorMatrix[15] = 1.0f;

    if (!mRelocRunning) {
        mRelocRunning = true;
        mRelocThread = std::thread(&MobileGS::relocThreadFunc, this);
    }
    if (!mMapRunning) {
        mMapRunning = true;
        mMapThread = std::thread(&MobileGS::mapThreadFunc, this);
    }
}

// Voxel/splat map deleted: GL-init stubs retained for the JNI/Kotlin surface (no map to init).
void MobileGS::initGl() {}
void MobileGS::initVoxelGl() {}
void MobileGS::initVoxelGlProgram() {}
void MobileGS::initVoxelGlBuffer() {}
void MobileGS::initMeshGl() {}

void MobileGS::resetGlContext() {
    initGl();
}

// Voxel/splat map deleted: render stubs retained for the JNI/Kotlin surface.
void MobileGS::draw(bool debugTint) {}

void MobileGS::drawDebugLayers(bool voxels, bool mesh) {}

void MobileGS::drawCoverage() {}

void MobileGS::pushPointCloud(const std::vector<float>& points) {}

// Voxel/splat map deleted: depth integration removed. The relocalizer uses color frames via
// scheduleRelocCheck, not this path. Stub retained so mapThreadFunc / pushFrame still compile.
void MobileGS::processDepthFrame(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, const float* intrinsics, bool isYuv, float confidence) {}

void MobileGS::mapThreadFunc() {
    setpriority(PRIO_PROCESS, 0, 15);
    JniThreadAttacher attacher;
    while (mMapRunning) {
        FrameData frame;
        {
            std::unique_lock<std::mutex> lock(mQueueMutex);
            mQueueCv.wait(lock,[this] { return !mFrameQueue.empty() || !mMapRunning; });
            if (!mMapRunning) break;
            frame = std::move(mFrameQueue.front());
            mFrameQueue.erase(mFrameQueue.begin());
        }
        processDepthFrame(frame.depth, frame.color, frame.viewMatrix, frame.projMatrix,
                          frame.hasIntrinsics ? frame.intrinsics : nullptr, frame.isYuv, frame.confidence);
    }
}

// Voxel/splat map deleted: depth frames are no longer cloned/queued (the queue was only
// drained-and-discarded). This removes the per-frame cv::Mat::clone() cost on the sensor thread.
// The idle map thread parks on mQueueCv and exits cleanly on destroy (mMapRunning=false + notify).
void MobileGS::pushFrame(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, const float* intrinsics, bool isYuv, float confidence) {}

void MobileGS::clearMap() {}                            // voxel/splat map deleted
void MobileGS::pruneByConfidence(float threshold) {}   // voxel/splat map deleted

void MobileGS::setParallaxMinDegrees(float deg) {}   // voxel/splat map deleted
void MobileGS::setVoxelSize(float size) { mVoxelSize = size; }

void MobileGS::updateCamera(float* viewMat, float* projMat) {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(mViewMatrix, viewMat, 16 * sizeof(float));
    memcpy(mProjMatrix, projMat, 16 * sizeof(float));
    mCameraReady = true;
}

void MobileGS::updateMappingCamera(float* viewMat, float* projMat) {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(mMappingViewMatrix, viewMat, 16 * sizeof(float));
    memcpy(mMappingProjMatrix, projMat, 16 * sizeof(float));
}

void MobileGS::updateLightLevel(float level) {
    std::lock_guard<std::mutex> lock(mMutex);
    mLightLevel = level;
}

void MobileGS::updateAnchorTransform(float* transformMat) {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(mAnchorMatrix, transformMat, 16 * sizeof(float));
}

void MobileGS::updateDeviceMotion(float* angularVel, float* linearVel) {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(mLastAngularVelocity, angularVel, 3 * sizeof(float));
    memcpy(mLastLinearVelocity, linearVel, 3 * sizeof(float));
}

void MobileGS::getAnchorTransform(float* outMat16) const {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(outMat16, mAnchorMatrix, 16 * sizeof(float));
}

void MobileGS::getConfidenceAvgs(float& outVisible, float& outGlobal) const {
    // Voxel/splat map deleted; reloc no longer depends on this (Kotlin confGlobal is decoupled).
    outVisible = 0.0f;
    outGlobal = 0.0f;
}

// Voxel/splat map deleted: SurfaceMesh removed.
void MobileGS::updatePersistentMesh(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat) {}
void MobileGS::getPersistentMesh(std::vector<float>& outVertices, std::vector<float>& outWeights) {}

/**
 * One relocalization attempt over one frame: snapshot the fingerprint, detect, match, solve, publish.
 *
 * EVALUATION.md 3.1 — split out of relocThreadFunc so an eval run can call it INLINE instead of
 * handing the frame to a background worker. Thread interleaving is one of the three named sources of
 * replay non-determinism: the worker's sleep is `locked ? 200 : 60` ms, so which frames it happens to
 * receive depends on scheduling, and two replays of the same recording sample the recording
 * differently. That turns a parameter A/B into a comparison of two different frame subsets, which is
 * the single most common way a tuning exercise produces confident nonsense.
 *
 * The extraction is deliberately mechanical — the body is unchanged except that the three
 * loop-level `continue`s became `return`s, since each meant "this attempt is over" and never
 * "skip to the next frame". Any other edit here would have been a relocalizer change smuggled in
 * behind an eval affordance.
 *
 * @param relocView the VIO view matrix snapshotted alongside the frame, for the rectifying warp.
 */
void MobileGS::runRelocPass(const cv::Mat& frame, const float* relocView) {
    cv::Mat wallDescs;
    std::vector<cv::Point3f> wallKps3d;
    // Phase 2: parallel to wallKps3d, or empty for a legacy fingerprint (= all backbone).
    std::vector<uint8_t> wallRegions;
    cv::Mat wallPatch;
    float fpIntrinsics[4];
    bool hasFpView = false;
    // Phase 2b snapshot: the persistent feature map + the last reloc pose, used (when the flag is on)
    // as the frustum-gate prior. The map is co-registered to the fingerprint anchor, so its points
    // share wallKps3d's frame and the prior pose (camera_from_fpWorld) projects them directly.
    cv::Mat mapDescs;
    std::vector<cv::Point3f> mapKps3d;
    float mapPriorPose[16];
    long mapPriorSeq = 0;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        wallDescs = mWallDescriptors.clone();
        wallKps3d = mWallKeypoints3D;
        wallRegions = mWallRegions;
        wallPatch = mWallPatch.clone();
        memcpy(fpIntrinsics, mFingerprintIntrinsics, 4 * sizeof(float));
        hasFpView = mHasFingerprintView;
        mapDescs = mMapDescriptors.clone();
        mapKps3d = mMapPoints3D;
        memcpy(mapPriorPose, mPnpCamFromFpWorld, 16 * sizeof(float));
        mapPriorSeq = mPnpResultSeq.load(std::memory_order_relaxed);
    }

    // 2.11: reset the backbone counters BEFORE the early-outs below, so an attempt that never
    // reaches the fingerprint publishes "not measured" instead of the previous attempt's numbers.
    mLastRelocBackboneFeatures.store(-1, std::memory_order_relaxed);
    mLastRelocBackboneMatches.store(-1, std::memory_order_relaxed);
    mLastRelocBackboneInliers.store(-1, std::memory_order_relaxed);
    // Same rule for the reprojection residual (IMPLEMENTATION.md 4.3): it is only meaningful
    // for the attempt that produced it, and the search radius reads it as a drift measurement.
    // Leaving a previous lock's value in place would widen or narrow the corroboration search
    // on the strength of a pose that is no longer the one being predicted from.
    mLastRelocReprojPx.store(-1.0f, std::memory_order_relaxed);
    // 3.2, same rule: the spread describes ONE attempt's inlier set. A stale value would let a
    // previous frame's well-spread lock authorise a promotion from this frame's clustered one,
    // which is the permanent-mutation version of the mistake the counters above guard against.
    mLastRelocInlierSpread.store(kPromotionNotMeasured, std::memory_order_relaxed);
    // ...and the same rule again for the corroboration counters (IMPLEMENTATION.md 4.6). These
    // are written only on a GATED attempt, so without a reset here one gated attempt's numbers
    // stayed live across every subsequent tick that fell back to the global search — a red
    // FEW_INLIERS row on the overlay sitting next to a healthy "Corrob 30/40", and, worse, the
    // same measurement copied into every eval CSV row until the next gated attempt, which turns
    // any rate E6 computes from these columns into a count of ticks rather than of attempts.
    // Reset alongside the backbone trio because they are the same kind of number and the
    // convention has to be one convention.
    mCorrobPredicted.store(-1, std::memory_order_relaxed);
    mCorrobMatched.store(-1, std::memory_order_relaxed);
    mCorrobLoneSkips.store(-1, std::memory_order_relaxed);
    mCorrobSearchRadiusPx.store(-1.0f, std::memory_order_relaxed);

    if (!mRelocEnabled) {
        mLastRelocReject.store(kRelocDisabled, std::memory_order_relaxed);
        return;
    }
    if (wallDescs.empty() || wallKps3d.empty()) {
        // No fingerprint, or one carrying descriptors but no 3D points (the depth-path result when
        // the depth API is off). Either way PnP has nothing to solve against, so the whole thread
        // idles here — silently, before this reject code existed.
        mLastRelocReject.store(kRelocNoFingerprint, std::memory_order_relaxed);
        return;
    }

    // IMPLEMENTATION.md 2.7 — honour the partition only when it indexes THIS point set. A
    // regions array of the wrong length is treated as absent (all backbone) rather than
    // subscripted, so a legacy or mismatched fingerprint relocalizes exactly as on main.
    // Hoisted out of buildCorr so the filter below and the count published here cannot drift
    // apart: they must agree about which fingerprint is partitioned or the diagnostics describe
    // a different point set from the one PnP saw.
    const bool usePartition = wallRegions.size() == wallKps3d.size();
    // 2.11: how many stored points PnP is allowed to draw on. An UNPARTITIONED fingerprint is
    // 2.7's legacy all-backbone case, so it reports its FULL point count — not zero, and not the
    // -1 sentinel. Zero here is a real measurement meaning F_out is empty (the artwork covers
    // the whole wall), which is the one state reloc cannot recover from; reporting a legacy
    // fingerprint as zero would raise that alarm on every project saved before Phase 2.
    mLastRelocBackboneFeatures.store(
        usePartition
            ? (int)std::count(wallRegions.begin(), wallRegions.end(), kRegionOutside)
            : (int)wallKps3d.size(),
        std::memory_order_relaxed);

    if (frame.empty()) return;

    // Optionally enhance the RGB frame under low light before grayscale conversion
    cv::Mat workFrame = frame;
    if (mEnhancer.isLoaded() && mLightLevel < kLowLightThreshold) {
        cv::Mat enhanced;
        if (mEnhancer.enhance(frame, enhanced)) workFrame = enhanced;
    }
    cv::Mat gray;
    cv::cvtColor(workFrame, gray, cv::COLOR_RGB2GRAY);
    normalizeForFeatures(gray); // illumination-normalize to match the (also-normalized) fingerprint

    // SuperPoint usable when loaded and the wall fingerprint is float-typed (or empty).
    const bool spOk = mSuperPoint.isLoaded() &&
        (wallDescs.empty() || wallDescs.type() == CV_32F);

    // Detect + Lowe-ratio match a gray image against the wall fingerprint. When Hback is non-empty
    // the matched keypoints are mapped through it (rectified frame -> current image) before being
    // stored, so the returned 2D points are ALWAYS in the current camera image — exactly what the
    // PnP below expects.
    // Detect base-frame features ONCE and reuse them: SuperPoint is an ONNX model, so detecting the
    // same gray twice (plain pass + map matching) would roughly double per-reloc cost. The scaled and
    // rectified passes run on different images, so buildCorr still detects internally for those.
    std::vector<cv::KeyPoint> baseKps; cv::Mat baseDescs;
    {
        bool sp = spOk;
        if (sp && !mSuperPoint.detect(gray, baseKps, baseDescs)) sp = false;
        if (!sp) mFeatureDetector->detectAndCompute(gray, cv::noArray(), baseKps, baseDescs);
    }
    mLastRelocDetected.store((int)baseKps.size(), std::memory_order_relaxed);

    auto buildCorr = [&](const cv::Mat& g, const cv::Mat& Hback,
                         std::vector<cv::Point2f>& outImg, std::vector<cv::Point3f>& outObj,
                         std::vector<uint8_t>& outFromBackbone,
                         const std::vector<cv::KeyPoint>* preKps = nullptr, const cv::Mat* preDescs = nullptr) {
        std::vector<cv::KeyPoint> localKps; cv::Mat localDescs;
        if (!(preKps && preDescs)) {
            bool sp = spOk;
            if (sp && !mSuperPoint.detect(g, localKps, localDescs)) sp = false;
            if (!sp) mFeatureDetector->detectAndCompute(g, cv::noArray(), localKps, localDescs);
        }
        const std::vector<cv::KeyPoint>& kps = (preKps && preDescs) ? *preKps : localKps;
        const cv::Mat& descs = (preKps && preDescs) ? *preDescs : localDescs;
        if (descs.empty() || wallDescs.empty()) return;
        if (descs.type() != wallDescs.type()) return;
        // trainIdx indexes wallDescs' ROWS but is used to subscript wallKps3d, so the two must be
        // the same length. Every in-tree producer keeps them aligned; a truncated or hand-edited
        // .gxr does not, and the result would be an out-of-bounds vector read feeding garbage 3D
        // points into solvePnPRansac. The map path (below) already guards this; the wall path did
        // not. Refuse rather than relocalize against nonsense.
        if (wallKps3d.size() != (size_t)wallDescs.rows) return;

        cv::Ptr<cv::DescriptorMatcher>& matcher = (descs.type() == CV_32F) ? mL2Matcher : mMatcher;
        std::vector<std::vector<cv::DMatch>> matches;
        matcher->knnMatch(descs, wallDescs, matches, 2);
        for (auto& match : matches) {
            if (match.size() < 2) continue;
            if (match[0].distance < kRelocLoweRatio * match[1].distance) {
                // PAPER.md §5: the reloc PnP solves the GLOBAL problem, with no prior, and must
                // therefore see only the backbone. Points under the artwork (INSIDE) are the
                // work surface — they decay as it is painted, which is exactly what makes
                // accuracy degrade with task progress. BAND straddles the edge and is trusted by
                // neither side. Corroboration against F_in happens later, once a pose exists.
                if (usePartition && wallRegions[match[0].trainIdx] != kRegionOutside) continue;
                cv::Point2f p = kps[match[0].queryIdx].pt;
                if (!Hback.empty()) {
                    std::vector<cv::Point2f> in{p}, outp;
                    cv::perspectiveTransform(in, outp, Hback);
                    p = outp[0];
                }
                outImg.push_back(p);
                outObj.push_back(wallKps3d[match[0].trainIdx]);
                // Everything that survives the filter above is F_out: under a partition because
                // non-OUTSIDE rows were skipped, and without one because 2.7's zero-length rule
                // makes the whole fingerprint backbone. Recorded per correspondence rather than
                // counted, because the inlier attribution below indexes back through this.
                outFromBackbone.push_back(1);
            }
        }
    };

    std::vector<cv::Point2f> imgPts;
    std::vector<cv::Point3f> objPts;
    // 2.11: parallel to imgPts/objPts — 1 where the correspondence came from a backbone point.
    std::vector<uint8_t> corrFromBackbone;
    buildCorr(gray, cv::Mat(), imgPts, objPts, corrFromBackbone, &baseKps, &baseDescs);

    // Multi-scale matching (distance robustness). SuperPoint isn't scale-invariant, and the marks
    // shrink in the frame from far away and grow up close, so also match the frame DOWN- and
    // UP-scaled, mapping the matched points back to full-res with a scale homography (Hback). These
    // passes share the plain pass's camera geometry, so they only add consistent correspondences
    // across distance; PnP RANSAC discards any that don't fit. Covers both ORB and SuperPoint
    // fingerprints, beyond ORB's own pyramid range.
    for (float s : {0.5f, 2.0f}) {
        cv::Mat scaled;
        cv::resize(gray, scaled, cv::Size(), s, s, cv::INTER_LINEAR);
        double hdata[] = {1.0/(double)s, 0.0, 0.0, 0.0, 1.0/(double)s, 0.0, 0.0, 0.0, 1.0};
        cv::Mat Hback = cv::Mat(3, 3, CV_64F, hdata).clone();
        buildCorr(scaled, Hback, imgPts, objPts, corrFromBackbone);
    }

    // Plane-guided rectification (perspective robustness for oblique views). The marks lie on a
    // known plane and VIO gives a pose, so the oblique-vs-frontal distortion is a homography we can
    // pre-cancel: warp the live frame into the fingerprint's frontal frame, match, and ADD the
    // correspondences mapped back to the current image (RANSAC filters any that don't fit).
    // Published so the diagnostics can show whether this pass is actually running. It was dead in
    // practice for a long time (nothing set mHasFingerprintView on the live capture path), so
    // "did rectification fire, and did it help" is worth being able to read off the device rather
    // than infer. -1 = the pass was not eligible at all this attempt.
    mLastRelocObliquityDeg.store(-1, std::memory_order_relaxed);
    mLastRelocRectifiedCorr.store(0, std::memory_order_relaxed);
    if (hasFpView && mIsArCoreTracking.load(std::memory_order_relaxed) && wallKps3d.size() >= 12) {
        cv::Mat Hcur_fp, Hfp_cur; double obliqDeg = 0.0;
        const bool haveH = computeRectifyHomography(relocView, Hcur_fp, Hfp_cur, obliqDeg);
        if (haveH) mLastRelocObliquityDeg.store((int)(obliqDeg + 0.5), std::memory_order_relaxed);
        if (haveH && obliqDeg > 25.0) {
            cv::Mat grayRect;
            cv::warpPerspective(gray, grayRect, Hfp_cur, gray.size());
            size_t before = imgPts.size();
            buildCorr(grayRect, Hcur_fp, imgPts, objPts, corrFromBackbone);
            mLastRelocRectifiedCorr.store((int)(imgPts.size() - before), std::memory_order_relaxed);
            if (imgPts.size() > before)
                LOGI("Reloc: rectified (obliquity %.0f deg) added %zu corr (total %zu)",
                     obliqDeg, imgPts.size() - before, imgPts.size());
        }
    }

    // --- Persistent feature-map matching (Phase 2b; default OFF via mMapRelocEnabled) ---
    // When the overlay is larger than the marks, the marks leave frame; the map carries features
    // across the whole wall so reloc still locks. Hard constraint: NEVER brute-force the whole map —
    // frustum-gate to the subset the last reloc pose says is in view, then match only those and
    // APPEND the correspondences (same fingerprint frame + intrinsics) so PnP solves over both.
    // Requires a prior pose (mapPriorSeq>0, i.e. the fingerprint has locked at least once) and a
    // matching descriptor type. Default-off, so this is inert until device-validated.
    if (mMapRelocEnabled.load(std::memory_order_relaxed) && !mapDescs.empty() && mapPriorSeq > 0
            && mapDescs.type() == wallDescs.type() && mapKps3d.size() == (size_t)mapDescs.rows) {
        glm::mat4 camFromFp = glm::make_mat4(mapPriorPose);
        double gfx = (fpIntrinsics[0] > 0.f) ? (double)fpIntrinsics[0] : 1000.0;
        double gfy = (fpIntrinsics[1] > 0.f) ? (double)fpIntrinsics[1] : 1000.0;
        double gcx = (fpIntrinsics[0] > 0.f) ? (double)fpIntrinsics[2] : gray.cols * 0.5;
        double gcy = (fpIntrinsics[1] > 0.f) ? (double)fpIntrinsics[3] : gray.rows * 0.5;
        std::vector<int> visible;
        visible.reserve(mapKps3d.size());
        for (int i = 0; i < (int)mapKps3d.size(); ++i) {
            glm::vec4 pc = camFromFp * glm::vec4(mapKps3d[i].x, mapKps3d[i].y, mapKps3d[i].z, 1.0f);
            if (pc.z <= 0.05f) continue; // behind / too close to the camera
            float u = (float)(gfx * pc.x / pc.z + gcx);
            float v = (float)(gfy * pc.y / pc.z + gcy);
            if (u >= 0.f && u < gray.cols && v >= 0.f && v < gray.rows) visible.push_back(i);
        }
        if (visible.size() >= 8) {
            // Preallocate the gated descriptor block with the right size+type and copy rows
            // (cv::Mat has no usable reserve() on an empty/typeless matrix). Reuse the base detection.
            cv::Mat gatedDescs((int)visible.size(), mapDescs.cols, mapDescs.type());
            for (size_t i = 0; i < visible.size(); ++i)
                mapDescs.row(visible[i]).copyTo(gatedDescs.row((int)i));
            if (!baseDescs.empty() && baseDescs.type() == gatedDescs.type()) {
                cv::Ptr<cv::DescriptorMatcher>& matcher = (baseDescs.type() == CV_32F) ? mL2Matcher : mMatcher;
                std::vector<std::vector<cv::DMatch>> matches;
                matcher->knnMatch(baseDescs, gatedDescs, matches, 2);
                size_t before = imgPts.size();
                for (auto& m : matches) {
                    if (m.size() < 2) continue;
                    if (m[0].distance < kRelocLoweRatio * m[1].distance) {
                        imgPts.push_back(baseKps[m[0].queryIdx].pt);
                        objPts.push_back(mapKps3d[visible[m[0].trainIdx]]);
                        // NOT backbone: the persistent map is a separate point set that Φ has
                        // never classified, so counting it in F_out would report a backbone the
                        // partition never vouched for — and mask an empty F_out on exactly the
                        // configuration (large overlay, marks off-frame) the map exists for.
                        corrFromBackbone.push_back(0);
                    }
                }
                if (imgPts.size() > before)
                    LOGI("Reloc map: gated %zu/%zu pts, added %zu corr (total %zu)",
                         visible.size(), mapKps3d.size(), imgPts.size() - before, imgPts.size());
            }
        }
    }

    // Distortion head (optional, docs/DISTORTION_HEAD.md): when the model + canonical patch are
    // present, compare the live view (cropped around the coarse match centroid) against the
    // fingerprint patch -> matchability (relock confidence) + coverage (= painting-progress). The
    // corners/H -> IPPE prior is a later increment; here we consume the cheap signals. Inert unless
    // the distortion_head.onnx asset is bundled. Uses RAW gray (the head's SuperPoint expects it).
    if (mDistortionHead.isLoaded() && !wallPatch.empty() && !imgPts.empty()) {
        float cxs = 0, cys = 0;
        for (const auto& p : imgPts) { cxs += p.x; cys += p.y; }
        cxs /= (float)imgPts.size(); cys /= (float)imgPts.size();
        cv::Mat headGray;
        cv::cvtColor(workFrame, headGray, cv::COLOR_RGB2GRAY);
        int side = std::min(headGray.cols, headGray.rows);
        int x0 = std::max(0, std::min((int)cxs - side / 2, headGray.cols - side));
        int y0 = std::max(0, std::min((int)cys - side / 2, headGray.rows - side));
        cv::Mat crop = headGray(cv::Rect(x0, y0, side, side)).clone();
        std::array<float, 13> dist{};
        if (mDistortionHead.run(crop, wallPatch, dist)) {
            const float matchability = dist[11], coverage = dist[12];
            if (matchability > 0.5f) {
                // A trusted look at the wall: it measures BOTH channels, and they are different
                // quantities. Coverage says how much of the design is realized (progress);
                // matchability says how much to trust this frame (confidence).
                mPaintingProgress.store(coverage, std::memory_order_relaxed);
                mCorroborationConfidence.store(matchability, std::memory_order_relaxed);
            } else {
                // The head looked and did not recognize the wall. That is a statement about THIS
                // FRAME, not about the mural, so only confidence decays. Decaying progress here
                // is what made a three-frame glitch read as the mural being un-painted.
                decayCorroboration();
            }
            LOGI("DistortionHead: match %.2f coverage %.2f tilt %.0f log2scale %.2f",
                 matchability, coverage, dist[8], dist[9]);
        } else {
            decayCorroboration();   // head refused to run — no information either way
        }
    } else if (mDistortionHead.isLoaded()) {
        // Head is loaded but there was no patch or no correspondences to centre the crop on, so
        // no attempt happened at all. Still a confidence statement, never a progress one.
        decayCorroboration();
    }

    // Lowered floors so a close-up PARTIAL view (only a corner of the marks visible) can still
    // localize: PnP needs only a handful of correspondences. The inlier RATIO (published below) is
    // the quality gate PoseFusion actually trusts, so being permissive here is safe.
    mLastRelocMatches.store((int)imgPts.size(), std::memory_order_relaxed);
    mLastRelocInliers.store(0, std::memory_order_relaxed);
    // corrFromBackbone is parallel to imgPts by construction, and the inlier attribution below
    // subscripts it with indices into imgPts. A future pass that appends to one and not the
    // other would make that read out of bounds, so a length disagreement publishes "not
    // measured" instead of a wrong number.
    const bool haveBackboneFlags = corrFromBackbone.size() == imgPts.size();
    // 2.11: counted SEPARATELY from mLastRelocMatches, never in place of it. The same shortfall
    // means different things — a low total is "the frame is not looking at the registered wall",
    // a low backbone under a healthy total is "everything it can see is under the artwork" — and
    // those call for opposite advice (aim differently vs. shrink the design / step back).
    mLastRelocBackboneMatches.store(
        haveBackboneFlags
            ? (int)std::count(corrFromBackbone.begin(), corrFromBackbone.end(), (uint8_t)1)
            : -1,
        std::memory_order_relaxed);
    if (imgPts.size() < 8) {
        // Distinguish "the detector found nothing / the descriptors don't even compare" from
        // "features were found but too few agreed with the fingerprint" — they call for opposite
        // fixes (light/focus/texture vs. aim at the registered area).
        mLastRelocReject.store(baseDescs.empty() ? kRelocNoFeatures : kRelocFewMatches,
                               std::memory_order_relaxed);
    }
    if (imgPts.size() >= 8) {
        cv::Mat rvec, tvec;
        std::vector<int> inliers;
        // Camera matrix: reuse the intrinsics the fingerprint's 3D points were built with (keeps
        // the 2D<->3D correspondence consistent) when available, else a coarse default. The old
        // hardcoded init supplied only 6 of the 9 entries, leaving the bottom row uninitialised.
        double fx = 1000.0, fy = 1000.0, cx = 960.0, cy = 540.0;
        if (fpIntrinsics[0] > 0.0f && fpIntrinsics[1] > 0.0f) {
            fx = fpIntrinsics[0]; fy = fpIntrinsics[1];
            cx = fpIntrinsics[2]; cy = fpIntrinsics[3];
        }
        double idata[] = {fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0};
        cv::Mat intr = cv::Mat(3, 3, CV_64F, idata).clone();
        StageTimer _pnpTimer(&mStageAccumMs[4], &mStageSamples[4]);
        // EVALUATION.md 3.1: solvePnPRansac draws random samples, so two replays of the same
        // recording can disagree and a parameter A/B reports RANSAC variance as an effect.
        // Re-seeded immediately before EVERY solve, not once at start-up: the reloc thread is
        // one of several consumers of the global cv::theRNG(), so a single seeding would drift
        // as soon as anything else drew from it. Negative = leave it alone, which is the
        // production default and keeps this inert outside an eval run.
        const long long evalSeed = mEvalRngSeed.load(std::memory_order_relaxed);
        if (evalSeed >= 0) cv::theRNG().state = (uint64_t)evalSeed;
        if (!cv::solvePnPRansac(objPts, imgPts, intr, cv::Mat(), rvec, tvec, false, 100, 8.0, 0.99, inliers)) {
            mLastRelocReject.store(kRelocPnpFailed, std::memory_order_relaxed);
        } else {
            mLastRelocInliers.store((int)inliers.size(), std::memory_order_relaxed);
            // 2.11: which of those inliers RANSAC drew from F_out. Left at -1 on the PnP-failed
            // branch above, because no inlier set exists there to attribute — that is "not
            // measured", not "no backbone agreed".
            if (haveBackboneFlags) {
                int backboneInliers = 0;
                for (int idx : inliers) {
                    if (idx >= 0 && idx < (int)corrFromBackbone.size() && corrFromBackbone[idx]) ++backboneInliers;
                }
                mLastRelocBackboneInliers.store(backboneInliers, std::memory_order_relaxed);
            }
            if (inliers.size() < 6) {
                mLastRelocReject.store(kRelocFewInliers, std::memory_order_relaxed);
            }
            if (inliers.size() >= 6) {
                // Refine on the RANSAC inliers. The marks lie on the wall plane, so resolve the
                // planar two-fold (flip) ambiguity with IPPE and keep whichever pose reprojects
                // best — but only adopt it if it strictly beats the RANSAC pose, so a non-coplanar
                // inlier set can never make relocalization worse.
                {
                    std::vector<cv::Point3f> inObj; std::vector<cv::Point2f> inImg;
                    inObj.reserve(inliers.size()); inImg.reserve(inliers.size());
                    for (int idx : inliers) { inObj.push_back(objPts[idx]); inImg.push_back(imgPts[idx]); }
                    // IMPLEMENTATION.md 3.2 — how much of the frame the inliers span, published
                    // here because this is the only scope that has them. The self-grow gate runs in
                    // tryUpdateFingerprint, which sees the inlier COUNT through an atomic and never
                    // the points, so without this the spread term could not exist at all.
                    //
                    // Measured against the frame the correspondences were found in (`gray`), not the
                    // fingerprint's capture frame: the box is a statement about this view's geometry.
                    mLastRelocInlierSpread.store(
                        inlierSpreadOf(inImg, (float)gray.cols, (float)gray.rows),
                        std::memory_order_relaxed);
                    auto reproj = [&](const cv::Mat& rv, const cv::Mat& tv) {
                        std::vector<cv::Point2f> pr;
                        cv::projectPoints(inObj, rv, tv, intr, cv::Mat(), pr);
                        double e = 0; for (size_t k = 0; k < pr.size(); ++k) e += cv::norm(pr[k] - inImg[k]);
                        return e;
                    };
                    double bestErr = reproj(rvec, tvec);
                    try {
                        std::vector<cv::Mat> rvecs, tvecs;
                        int n = cv::solvePnPGeneric(inObj, inImg, intr, cv::Mat(), rvecs, tvecs,
                                                    false, cv::SOLVEPNP_IPPE);
                        for (int s = 0; s < n; ++s) {
                            double e = reproj(rvecs[s], tvecs[s]);
                            if (e < bestErr) { bestErr = e; rvecs[s].copyTo(rvec); tvecs[s].copyTo(tvec); }
                        }
                    } catch (const cv::Exception&) { /* keep RANSAC pose */ }
                    // IMPLEMENTATION.md 4.3 — publish the MEAN, not the sum `reproj` returns.
                    // The sum grows with the inlier count, so a better lock would report a worse
                    // error and the corroboration search would tighten exactly when it has the
                    // most to gain from staying put. Divided by the same set it was summed over.
                    if (!inObj.empty()) {
                        mLastRelocReprojPx.store((float)(bestErr / (double)inObj.size()),
                                                 std::memory_order_relaxed);
                    }
                }
                cv::Mat R;
                cv:: Rodrigues(rvec, R);

                // PnP gives T_camera_from_fingerprintWorld (a view matrix). DO NOT write it to
                // mAnchorMatrix (a world-space MODEL matrix) — that caused overlay teleport.
                // Publish the raw result; Kotlin composes inverse(V_current)*pnp*fpAnchor with the
                // FRESH view matrix (see PoseFusion).
                glm::mat4 pnpMat = glm::mat4(1.0f);
                for(int i=0; i<3; ++i) {
                    for(int j=0; j<3; ++j) pnpMat[j][i] = (float)R.at<double>(i,j);
                    pnpMat[3][i] = (float)tvec.at<double>(i);
                }
                {
                    std::lock_guard<std::mutex> lock(mMutex);
                    memcpy(mPnpCamFromFpWorld, glm::value_ptr(pnpMat), 16 * sizeof(float));
                }
                mPnpInlierCount.store((int)inliers.size(), std::memory_order_relaxed);
                mPnpMatchCount.store((int)imgPts.size(), std::memory_order_relaxed);
                mPnpResultSeq.fetch_add(1, std::memory_order_relaxed);
                mLastRelocReject.store(kRelocOk, std::memory_order_relaxed);
                LOGI("Relocalization: PnP match published (%zu/%zu inliers)", inliers.size(), imgPts.size());
                // Phase 3 passive build: grow the feature map from this locked frame (default OFF).
                if (mMapBuildEnabled.load(std::memory_order_relaxed))
                    growMapFromReloc(pnpMat, baseKps, baseDescs, fx, fy, cx, cy);
            }
        }
    }

    // Teleological gatekeeper: update painting-progress from how much of the artwork base the clean
    // frame now corroborates. No-op until an artwork is registered; read-only on the reloc set.
    // Hands over this frame's detection so it isn't recomputed (see tryUpdateFingerprint).
    tryUpdateFingerprint(gray, &baseKps, &baseDescs);
}

void MobileGS::relocThreadFunc() {
    setpriority(PRIO_PROCESS, 0, 10); // Standard background priority
    JniThreadAttacher attacher;
    while (mRelocRunning) {
        cv::Mat frame;
        float relocView[16];
        {
            std::unique_lock<std::mutex> lock(mRelocMutex);
            mRelocCv.wait(lock, [this] { return mRelocRequested || !mRelocRunning; });
            if (!mRelocRunning) break;
            frame = mRelocColorFrame.clone();
            memcpy(relocView, mRelocViewMatrix, 16 * sizeof(float));
            mRelocRequested = false;
        }

        // The whole attempt. In EVAL SYNC MODE this worker never gets here: scheduleRelocCheck runs
        // the pass on the caller's thread and never sets mRelocRequested, so the wait above simply
        // parks. (Switching modes mid-run can let one already-queued request through; the flag is an
        // eval affordance set before a run starts, not a live toggle.)
        runRelocPass(frame, relocView);

        // Back off only once locked. A flat 200 ms capped every state at 5 Hz, including the one that
        // matters most — hunting for the first lock, or re-acquiring after the artist looks away —
        // where the cost of an extra attempt is far smaller than the cost of the overlay staying
        // adrift. Locked and stable, 200 ms is plenty and keeps the thermal/battery profile.
        const bool locked = mLastRelocReject.load(std::memory_order_relaxed) == kRelocOk;
        std::this_thread::sleep_for(std::chrono::milliseconds(locked ? 200 : 60));
    }
}
bool MobileGS::computeRectifyHomography(const float* viewCur16, cv::Mat& Hcur_fp,
                                        cv::Mat& Hfp_cur, double& obliquityDeg) {
    glm::mat4 viewCur = glm::make_mat4(viewCur16);
    glm::mat4 viewFp;
    double fx, fy, cx, cy;
    std::vector<cv::Point3f> pts;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (!mHasFingerprintView) return false;
        viewFp = glm::make_mat4(mFingerprintViewMatrix);
        fx = mFingerprintIntrinsics[0]; fy = mFingerprintIntrinsics[1];
        cx = mFingerprintIntrinsics[2]; cy = mFingerprintIntrinsics[3];
        pts = mWallKeypoints3D;
    }
    if (pts.size() < 12 || fx <= 0.0 || fy <= 0.0) return false;

    // Fit a plane to the fingerprint-frame 3D marks: centroid + normal (smallest-variance PCA axis).
    cv::Mat data((int)pts.size(), 3, CV_32F);
    for (int i = 0; i < (int)pts.size(); ++i) {
        data.at<float>(i,0) = pts[i].x; data.at<float>(i,1) = pts[i].y; data.at<float>(i,2) = pts[i].z;
    }
    cv::PCA pca(data, cv::Mat(), cv::PCA::DATA_AS_ROW);
    cv::Vec3d n(pca.eigenvectors.at<float>(2,0), pca.eigenvectors.at<float>(2,1), pca.eigenvectors.at<float>(2,2));
    cv::Vec3d c(pca.mean.at<float>(0,0), pca.mean.at<float>(0,1), pca.mean.at<float>(0,2));
    double nn = cv::norm(n); if (nn < 1e-6) return false; n /= nn;
    double d = n.dot(c);
    if (d < 0) { n = -n; d = -d; }              // plane n·X = d with d > 0 (in front of the fp camera)
    if (d < 1e-3) return false;

    // Relative pose fp-camera -> current-camera (both share the VIO world while tracking):
    //   X_cur = R X_fp + t,  T = view_cur * inverse(view_fp).  glm is column-major: T[col][row].
    glm::mat4 T = viewCur * glm::inverse(viewFp);
    cv::Matx33d Rgl(T[0][0], T[1][0], T[2][0],
                    T[0][1], T[1][1], T[2][1],
                    T[0][2], T[1][2], T[2][2]);
    cv::Vec3d tgl(T[3][0], T[3][1], T[3][2]);

    // ARCore view matrices are OpenGL convention (camera looks down -z, +y up); the fingerprint 3D
    // points are OpenCV convention (+z forward, +y down). Convert the pose with C = diag(1,-1,-1)
    // (its own inverse): R_cv = C R_gl C, t_cv = C t_gl. Without this the homography is meaningless.
    const cv::Matx33d C(1,0,0, 0,-1,0, 0,0,-1);
    cv::Matx33d R = C * Rgl * C;
    cv::Vec3d t = C * tgl;

    // Obliquity = angle between the plane normal in the CURRENT camera frame and the optical (+z) axis.
    cv::Vec3d nCur = R * n;
    double cosA = std::abs(nCur[2]) / (cv::norm(nCur) + 1e-9);
    obliquityDeg = std::acos(std::min(1.0, cosA)) * 180.0 / CV_PI;

    // Plane-induced homography current-image <- fingerprint-image:  H = K (R - t nᵀ / d) K⁻¹.
    cv::Matx33d K(fx, 0, cx, 0, fy, cy, 0, 0, 1);
    cv::Matx33d M = R - (1.0 / d) * (cv::Matx31d(t[0], t[1], t[2]) * cv::Matx13d(n[0], n[1], n[2]));
    cv::Matx33d Hc = K * M * K.inv();
    if (std::abs(Hc(2,2)) < 1e-9) return false;
    Hc = (1.0 / Hc(2,2)) * Hc;
    Hcur_fp = cv::Mat(Hc);
    Hfp_cur = Hcur_fp.inv();
    return true;
}

std::vector<uint8_t> MobileGS::exportWallFeatureMap() const {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mMapPoints3D.empty() || mMapDescriptors.empty() ||
        mMapPoints3D.size() != (size_t)mMapDescriptors.rows) return {};  // never export an inconsistent map
    cv::Mat dm = mMapDescriptors.isContinuous() ? mMapDescriptors : mMapDescriptors.clone();
    const int32_t n = (int32_t)mMapPoints3D.size();
    const int32_t descRows = dm.rows, descCols = dm.cols, descType = dm.type();
    std::vector<float> conf = mMapConfidence; conf.resize(n, 1.0f);                 // defensive align
    std::vector<int32_t> obs(mMapObs.begin(), mMapObs.end()); obs.resize(n, 1);
    const size_t descBytes = dm.total() * dm.elemSize();
    const size_t total = 4 * sizeof(int32_t) + (size_t)n * 3 * sizeof(float)
                       + (size_t)n * sizeof(float) + (size_t)n * sizeof(int32_t)
                       + 16 * sizeof(float) + 4 * sizeof(float) + descBytes;
    std::vector<uint8_t> out(total);
    uint8_t* p = out.data();
    auto put = [&](const void* src, size_t len){ memcpy(p, src, len); p += len; };
    put(&n, sizeof(int32_t)); put(&descRows, sizeof(int32_t));
    put(&descCols, sizeof(int32_t)); put(&descType, sizeof(int32_t));
    put(mMapPoints3D.data(), (size_t)n * 3 * sizeof(float)); // cv::Point3f = 3 contiguous floats
    put(conf.data(), (size_t)n * sizeof(float));
    put(obs.data(), (size_t)n * sizeof(int32_t));
    put(mMapAnchorMatrix, 16 * sizeof(float));
    put(mMapIntrinsics, 4 * sizeof(float));
    if (descBytes) put(dm.data, descBytes);
    return out;
}

void MobileGS::growMapFromReloc(const glm::mat4& camFromFp, const std::vector<cv::KeyPoint>& kps,
                                const cv::Mat& descs, double fx, double fy, double cx, double cy) {
    if (descs.empty() || kps.empty() || (int)kps.size() != descs.rows) return;
    std::lock_guard<std::mutex> lock(mMutex);
    if (mWallKeypoints3D.size() < 8) return;                                   // need the fingerprint plane
    if (!mMapDescriptors.empty() && mMapDescriptors.type() != descs.type()) return;
    if (mMapPoints3D.size() != (size_t)mMapDescriptors.rows) return;  // corrupted map: bail rather than crash

    // Keep the parallel arrays aligned with the points: a restored map may have carried points +
    // descriptors but empty confidence/obs (both optional in WallFeatureMap). Without this, the add
    // path below would desync them from mMapPoints3D and corrupt per-point confidence.
    if (mMapConfidence.size() != mMapPoints3D.size()) mMapConfidence.resize(mMapPoints3D.size(), 1.0f);
    if (mMapObs.size() != mMapPoints3D.size()) mMapObs.resize(mMapPoints3D.size(), 1);

    // Confidence-prune when at capacity so the map keeps refreshing within the cap (drop points that
    // never earned a re-observation). Compacts all four parallel arrays + the descriptor matrix.
    const size_t kMapCap = 5000;
    if (mMapPoints3D.size() >= kMapCap) {
        std::vector<size_t> kept;
        kept.reserve(mMapPoints3D.size());
        for (size_t i = 0; i < mMapPoints3D.size(); ++i)
            if (mMapConfidence[i] >= 0.2f) kept.push_back(i);
        std::vector<cv::Point3f> np; np.reserve(kept.size());
        std::vector<float> nc; nc.reserve(kept.size());
        std::vector<int> no; no.reserve(kept.size());
        cv::Mat nd;
        if (!kept.empty()) {
            nd.create((int)kept.size(), mMapDescriptors.cols, mMapDescriptors.type());
            for (size_t idx = 0; idx < kept.size(); ++idx) {
                size_t i = kept[idx];
                np.push_back(mMapPoints3D[i]); nc.push_back(mMapConfidence[i]); no.push_back(mMapObs[i]);
                mMapDescriptors.row((int)i).copyTo(nd.row((int)idx));
            }
        }
        mMapPoints3D.swap(np); mMapConfidence.swap(nc); mMapObs.swap(no); mMapDescriptors = nd;
    }

    // Fit the wall plane (centroid + normal) from the fingerprint's 3D points (in the fingerprint frame).
    cv::Point3f c(0.f, 0.f, 0.f);
    for (const auto& p : mWallKeypoints3D) c += p;
    c *= 1.0f / (float)mWallKeypoints3D.size();
    double cov[6] = {0,0,0,0,0,0}; // xx,xy,xz,yy,yz,zz
    for (const auto& p : mWallKeypoints3D) {
        double dx = p.x - c.x, dy = p.y - c.y, dz = p.z - c.z;
        cov[0]+=dx*dx; cov[1]+=dx*dy; cov[2]+=dx*dz; cov[3]+=dy*dy; cov[4]+=dy*dz; cov[5]+=dz*dz;
    }
    cv::Matx33d C(cov[0],cov[1],cov[2], cov[1],cov[3],cov[4], cov[2],cov[4],cov[5]);
    cv::Vec3d eval; cv::Matx33d evec;
    if (!cv::eigen(C, eval, evec)) return;
    glm::vec3 n((float)evec(2,0), (float)evec(2,1), (float)evec(2,2));   // smallest-eigenvalue eigenvector
    glm::vec3 cc(c.x, c.y, c.z);

    // Associate detected features to the existing map by descriptor; bump confidence on re-observation.
    std::vector<char> matched(kps.size(), 0);
    if (mMapDescriptors.rows >= 2) {   // knnMatch(k=2) needs >=2 candidates for the Lowe ratio
        cv::Ptr<cv::DescriptorMatcher>& matcher = (descs.type() == CV_32F) ? mL2Matcher : mMatcher;
        std::vector<std::vector<cv::DMatch>> matches;
        matcher->knnMatch(descs, mMapDescriptors, matches, 2);
        for (auto& m : matches) {
            if (m.size() < 2) continue;
            if (m[0].distance < kRelocLoweRatio * m[1].distance) {
                int ti = m[0].trainIdx, qi = m[0].queryIdx;
                if (ti >= 0 && ti < (int)mMapConfidence.size() && qi >= 0 && qi < (int)matched.size()) {
                    mMapConfidence[ti] = std::min(1.0f, mMapConfidence[ti] + 0.1f);
                    mMapObs[ti] += 1;
                    matched[qi] = 1;
                }
            }
        }
    }

    // Back-project unmatched features onto the wall plane and add them, up to the cap.
    glm::mat4 fpFromCam = glm::inverse(camFromFp);
    glm::vec3 camCenter(fpFromCam[3][0], fpFromCam[3][1], fpFromCam[3][2]);
    glm::mat3 R = glm::mat3(fpFromCam);
    int added = 0;
    for (size_t i = 0; i < kps.size(); ++i) {
        if (matched[i]) continue;
        if (mMapPoints3D.size() >= kMapCap) break;
        glm::vec3 dir = R * glm::vec3((float)((kps[i].pt.x - cx) / fx),
                                      (float)((kps[i].pt.y - cy) / fy), 1.0f);
        float denom = glm::dot(n, dir);
        if (std::fabs(denom) < 1e-6f) continue;
        float t = glm::dot(n, cc - camCenter) / denom;
        if (t <= 0.f) continue;            // plane intersection behind the camera
        glm::vec3 P = camCenter + t * dir;
        mMapPoints3D.push_back(cv::Point3f(P.x, P.y, P.z));
        mMapConfidence.push_back(0.1f);
        mMapObs.push_back(1);
        mMapDescriptors.push_back(descs.row((int)i));
        ++added;
    }

    // Co-register the map to the fingerprint anchor + intrinsics (same frame as the points above).
    memcpy(mMapAnchorMatrix, mFingerprintAnchorMatrix, 16 * sizeof(float));
    mMapIntrinsics[0]=(float)fx; mMapIntrinsics[1]=(float)fy; mMapIntrinsics[2]=(float)cx; mMapIntrinsics[3]=(float)cy;
    if (added > 0) LOGI("Map build: +%d pts (map now %zu)", added, mMapPoints3D.size());
}

void MobileGS::tryUpdateFingerprint(const cv::Mat& grayClean,
                                    const std::vector<cv::KeyPoint>* preKps,
                                    const cv::Mat* preDescs) {
    cv::Mat artDescs;
    std::vector<cv::Point2f> artPts2d;
    int artImgW = 0, artImgH = 0;
    long artGeneration = 0;
    bool havePlacement = false;
    float fpFromDesign[16];
    float designHalfW = 0.0f, designHalfH = 0.0f;
    float camFromFp[16];
    float fpFx = 0.0f, fpFy = 0.0f, fpCx = 0.0f, fpCy = 0.0f;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mArtworkDescriptors.empty()) return;
        artDescs = mArtworkDescriptors;
        artPts2d = mArtworkKeypoints2D;
        artImgW = mArtworkImageW;
        artImgH = mArtworkImageH;
        artGeneration = mArtworkGeneration;
        havePlacement = mHasDesignPlacement;
        memcpy(fpFromDesign, mDesignFpFromDesign, 16 * sizeof(float));
        designHalfW = mDesignHalfW;
        designHalfH = mDesignHalfH;
        memcpy(camFromFp, mPnpCamFromFpWorld, 16 * sizeof(float));
        fpFx = mFingerprintIntrinsics[0]; fpFy = mFingerprintIntrinsics[1];
        fpCx = mFingerprintIntrinsics[2]; fpCy = mFingerprintIntrinsics[3];
    }
    if (grayClean.empty()) return;

    // Detect on the CLEAN frame (the real wall incl. any new paint — overlays are GL-only, never in
    // this CV frame) and match against the ARTWORK base: a clean feature corroborates the target only
    // if it matches what the artwork expects. This is the validator for the upcoming self-grow.
    //
    // Reuse the caller's detection when it is usable: the reloc thread has already run the detector
    // over this identical frame, and with SuperPoint that is a whole ONNX forward pass. A descriptor
    // type mismatch can't knnMatch against the artwork anyway, so that case re-detects.
    std::vector<cv::KeyPoint> localKps; cv::Mat localDescs;
    const bool reuse = preKps && preDescs && !preDescs->empty() && preDescs->type() == artDescs.type();
    if (!reuse) {
        bool sp = mSuperPoint.isLoaded() && (artDescs.type() == CV_32F);
        if (sp && !mSuperPoint.detect(grayClean, localKps, localDescs)) sp = false;
        if (!sp) mFeatureDetector->detectAndCompute(grayClean, cv::noArray(), localKps, localDescs);
    }
    const std::vector<cv::KeyPoint>& kps = reuse ? *preKps : localKps;
    const cv::Mat& descs = reuse ? *preDescs : localDescs;
    if (descs.empty() || descs.type() != artDescs.type()) return;

    // IMPLEMENTATION.md 4.5 — is the SPATIALLY-CONSTRAINED match available for this frame?
    //
    // Four things have to hold, and each missing one is a refusal rather than a guess:
    //  - the design's placement on the wall, pushed from Kotlin (setDesignPlacement);
    //  - a pose for THIS frame. mLastRelocReject is written by the attempt that just ran, a few
    //    lines above the call into here, so kRelocOk means mPnpCamFromFpWorld describes this frame
    //    and not some earlier lock. Predicting from a stale pose is precisely the failure the phase
    //    warns about, and it fails silently — the search looks in a confidently wrong place;
    //  - the artwork's 2D positions, one per descriptor row. A length mismatch means the two fell
    //    out of step and every prediction after that point would be another feature's;
    //  - the intrinsics the fingerprint's 3D was built with, which the reloc PnP also uses.
    //
    // When any of them is missing this falls back to the global knnMatch below, which is exactly
    // pre-Phase-4 behaviour.
    const bool gated =
        havePlacement &&
        mLastRelocReject.load(std::memory_order_relaxed) == kRelocOk &&
        (int)artPts2d.size() == artDescs.rows &&
        artImgW > 0 && artImgH > 0 && fpFx > 0.0f && fpFy > 0.0f;

    std::vector<char> hit(artDescs.rows, 0);
    std::vector<int> validQuery;   // clean keypoints that corroborate the artwork (self-grow candidates)
    int matched = 0;
    int predicted = -1;            // -1 = no gated attempt ran; 0 is a real reading
    int loneSkips = -1;            // likewise: 0 skips is a measurement, not an absence
    float radiusPx = -1.0f;

    if (gated) {
        // camera_from_design = camera_from_fingerprintWorld * fingerprintWorld_from_design. Both are
        // column-major, which is glm's layout, so make_mat4 is a reinterpretation and not a
        // transpose. The right-hand factor is the SAME composition Phi is classified with — see
        // setDesignPlacement — deliberately, so the two cannot disagree about where the design is.
        const glm::mat4 camFromDesign = glm::make_mat4(camFromFp) * glm::make_mat4(fpFromDesign);
        // Distance to the wall along the view axis: the design origin's depth in the camera frame.
        // OpenCV convention (+Z forward), because that matrix came out of solvePnP.
        const float wallDistM = camFromDesign[3][2];
        const float focalPx = 0.5f * (fpFx + fpFy);
        // poseErrMm is unconditionally "not measured" here and that is not an oversight: the only
        // producer is DriftCostProbe, which needs a ground-truth pose and returns its own -1 sentinel
        // without one. The reprojection residual below is the drift signal that exists on this path.
        radiusPx = searchradius::pixels(
            searchradius::kRho, designHalfW, designHalfH, wallDistM, focalPx,
            /*poseErrMm=*/searchradius::kNotMeasured,
            /*reprojErrPx=*/mLastRelocReprojPx.load(std::memory_order_relaxed));

        KeypointGrid grid;
        grid.build(kps, radiusPx);

        // Descriptor distance, computed directly rather than through a matcher: the query here is
        // "these few candidates", and building a per-feature cv::Mat to hand to knnMatch would cost
        // more than the comparison. L2 for SuperPoint (CV_32F), Hamming for ORB — the same pairing
        // mL2Matcher/mMatcher encode, and the type equality was checked above.
        const bool isFloat = descs.type() == CV_32F;
        const int dcols = descs.cols;
        auto descDistance = [&](int q, int a) -> float {
            if (isFloat) {
                const float* pq = descs.ptr<float>(q);
                const float* pa = artDescs.ptr<float>(a);
                float acc = 0.0f;
                for (int k = 0; k < dcols; ++k) { const float d = pq[k] - pa[k]; acc += d * d; }
                return std::sqrt(acc);
            }
            const uchar* pq = descs.ptr<uchar>(q);
            const uchar* pa = artDescs.ptr<uchar>(a);
            int acc = 0;
            for (int k = 0; k < dcols; ++k) acc += __builtin_popcount((unsigned)(pq[k] ^ pa[k]));
            return (float)acc;
        };

        const float frameW = (float)grayClean.cols;
        const float frameH = (float)grayClean.rows;
        std::vector<int> cand;
        predicted = 0;
        int loneCandidateSkips = 0;
        for (int a = 0; a < artDescs.rows; ++a) {
            const cv::Point2f& ap = artPts2d[(size_t)a];
            // Composite pixel -> design-plane metres. The overlay quad spans [-halfW, halfW] x
            // [-halfH, halfH] with the texture stretched across it and V flipped (OverlayRenderer
            // writes `1 - v` alongside `y = -halfH + v * 2 * halfH`), so image row 0 is +halfH. The
            // mapping is parametric in the composite's size, which is why it needs no aspect
            // agreement between the bitmap and the quad.
            const float lx = designHalfW * (2.0f * ap.x / (float)artImgW - 1.0f);
            const float ly = designHalfH * (1.0f - 2.0f * ap.y / (float)artImgH);
            const glm::vec4 pc = camFromDesign * glm::vec4(lx, ly, 0.0f, 1.0f);
            if (!(pc.z > 1e-4f)) continue;                       // behind or on the camera plane
            const float u = fpFx * pc.x / pc.z + fpCx;
            const float v = fpFy * pc.y / pc.z + fpCy;
            if (!std::isfinite(u) || !std::isfinite(v)) continue;
            // Predicted OUTSIDE the frame is not visible, and must not count in the denominator —
            // that is the whole of 5b.1. The radius is allowed as slack on each edge because a
            // feature predicted just off-frame can still have its true detection just on it.
            if (u < -radiusPx || v < -radiusPx || u > frameW + radiusPx || v > frameH + radiusPx) continue;
            ++predicted;

            grid.candidatesWithin(u, v, radiusPx, cand);
            if (cand.size() < 2) {
                // Lowe's ratio needs a second-best to divide by. With one candidate there is nothing
                // to compare against, and accepting it unconditionally would corroborate any
                // keypoint that happens to land near the prediction regardless of what it looks
                // like — a confidence signal that measures the wall's texture density instead of its
                // agreement with the design, feeding PoseFusion's correction strength.
                //
                // Skipping deflates `matched` without touching `predicted`, so the cost shows up
                // as lower confidence — but "lower confidence" is not free: it maps through
                // PoseFusion's alpha to LESS relocalization correction and therefore more
                // accumulated drift, which is the failure corroboration exists to prevent. So this
                // is conservative against false snapping and anti-conservative against drift, and
                // the only thing that tells you which side you are on is the rate.
                //
                // Published on its own diagnostic channel rather than logged, because at the MIN_PX
                // floor a sparse frame can skip most of what it predicted and the symptom — a wall
                // that has apparently stopped corroborating — looks identical to an unpainted one.
                // E6 sets rho against this number.
                if (cand.size() == 1) ++loneCandidateSkips;
                continue;
            }
            float best = FLT_MAX, second = FLT_MAX;
            int bestQ = -1;
            for (int q : cand) {
                const float d = descDistance(q, a);
                if (d < best) { second = best; best = d; bestQ = q; }
                else if (d < second) { second = d; }
            }
            if (bestQ < 0) continue;
            if (best < kCorrobLoweRatio * second) {
                hit[a] = 1;
                ++matched;
                validQuery.push_back(bestQ);
            }
        }
        loneSkips = loneCandidateSkips;
        if (loneCandidateSkips > 0) {
            LOGI("Corroboration (gated): r=%.1fpx predicted=%d matched=%d lone-candidate skips=%d",
                 radiusPx, predicted, matched, loneCandidateSkips);
        }
    } else {
        // Pre-Phase-4 global search: every frame descriptor against every artwork descriptor, no
        // prior, tight ratio. Note the direction is the opposite of the gated path's — here each
        // FRAME keypoint asks which design feature it is, because there is no predicted location to
        // ask the question the other way round.
        cv::Ptr<cv::DescriptorMatcher>& matcher = (descs.type() == CV_32F) ? mL2Matcher : mMatcher;
        std::vector<std::vector<cv::DMatch>> matches;
        matcher->knnMatch(descs, artDescs, matches, 2);
        for (auto& m : matches) {
            if (m.size() < 2) continue;
            if (m[0].distance < kRelocLoweRatio * m[1].distance) {
                int a = m[0].trainIdx;
                if (a >= 0 && a < (int)hit.size() && !hit[a]) { hit[a] = 1; matched++; }
                validQuery.push_back(m[0].queryIdx);
            }
        }
    }

    // IMPLEMENTATION.md 4.6 — publish what the match actually did. Only the gated path writes these:
    // the global path measures a different quantity (the whole design, framing included) and
    // reporting its numbers in the same channels would make the two look comparable.
    if (gated) {
        mCorrobPredicted.store(predicted, std::memory_order_relaxed);
        mCorrobMatched.store(matched, std::memory_order_relaxed);
        mCorrobLoneSkips.store(loneSkips, std::memory_order_relaxed);
        mCorrobSearchRadiusPx.store(radiusPx, std::memory_order_relaxed);
    }

    // Fold this attempt's hits into the running corroboration counts, under the generation check —
    // the artwork may have been replaced while the match ran.
    //
    // GATED ATTEMPTS ONLY. The fallback branch above is the pre-Phase-4 global search: an
    // unconstrained descriptor match with no geometric agreement behind it, and feeding a signal
    // that never decays from a match that was never localized is how a monotone counter saturates
    // on noise. A gated hit means the wall shows this design feature within a few pixels of where
    // the design says it should be AND wins the ratio test among its neighbours; that is a
    // materially stronger claim, and it is the only one allowed to move progress.
    int everCorroborated = -1;
    if (gated) {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mArtworkGeneration == artGeneration &&
            mArtworkCorroborated.size() == (size_t)artDescs.rows) {
            everCorroborated = 0;
            for (int a = 0; a < artDescs.rows; ++a) {
                uint8_t& c = mArtworkCorroborated[(size_t)a];
                if (hit[a] && c < kCorrobConfirmations) ++c;
                if (c >= kCorrobConfirmations) ++everCorroborated;
            }
        }
    }

    // The distortion head's coverage is the principled progress signal; only fall back to these
    // descriptor-corroboration ratios when the head isn't present.
    //
    // The two channels have DIFFERENT denominators now, which is the point of Phase 4/5b.
    //
    // PROGRESS is cumulative over the whole design: how much of the artwork the wall has ever
    // answered for. It has to be cumulative because the gated match only ever looks at the part of
    // the design in frame, so an instantaneous ratio would measure where the artist is standing. It
    // is also what getPaintingProgress() already promises — hours, roughly monotonic, never decayed.
    //
    // CONFIDENCE is instantaneous over the predicted-visible set: of the design features the current
    // pose says should be in view, how many does the wall back right now. A close-up of one corner
    // is no longer capped by framing rather than by agreement (5b.1).
    //
    // Both channels switch definition on `havePlacement`, NOT on `gated`. A design placement is
    // stable across frames while a lock is not, so keying on the lock would flip progress between
    // two different measurements every time the artist looked away and back — two definitions
    // alternating in one readout is worse than either alone.
    if (!mDistortionHead.isLoaded()) {
        if (havePlacement) {
            // Phase 4 is active for this project. A tick with no lock publishes nothing and leaves
            // the last value standing, because the alternative is replacing a cumulative reading
            // with an instantaneous one from a different denominator.
            if (everCorroborated >= 0 && artDescs.rows > 0) {
                mPaintingProgress.store((float)everCorroborated / (float)artDescs.rows,
                                        std::memory_order_relaxed);
            }
        } else if (artDescs.rows > 0) {
            // No placement ever pushed: Phase 4 is off for this project and this is exactly the
            // pre-Phase-4 instantaneous whole-design ratio.
            mPaintingProgress.store((float)matched / (float)artDescs.rows,
                                    std::memory_order_relaxed);
        }

        if (gated) {
            // 4.8: zero predicted-visible is "the artist is not looking at the design", NOT "the
            // wall does not corroborate it". Publishing 0.0 would be a measurement never taken.
            //
            // Note what this does and does not buy. PoseFusion cannot tell the two apart —
            // ArRenderer coerces the negative to 0f and CONF_FLOOR absorbs both, so `effConf` is
            // byte-identical either way. The value is entirely in the diagnostics and the eval CSV,
            // which is where E6 has to separate "looking away" from "looked, found nothing".
            mCorroborationConfidence.store(
                predicted > 0 ? (float)matched / (float)predicted : kCorroborationUnmeasured,
                std::memory_order_relaxed);
        } else if (!havePlacement && artDescs.rows > 0) {
            mCorroborationConfidence.store((float)matched / (float)artDescs.rows,
                                           std::memory_order_relaxed);
        } else {
            // Placement exists but this attempt had no pose to predict from. That is an attempt
            // that ran and produced no usable measurement, which is precisely what the decay is
            // for — the last reading is getting stale, and saying so beats repeating it.
            decayCorroboration();
        }
    }

    // --- Teleological self-grow (opt-in) ---------------------------------------------------------
    // Promote validated NEW marks into the live reloc fingerprint so it self-grows from real painting
    // that matches the target — surviving the original marks being painted over. Depth-free: each new
    // feature is placed on the wall plane via the current relocalized pose. Guarded hard (fresh +
    // confident relock, gatekeeper-validated, capped, deduped) and RANSAC in the reloc PnP is the
    // backstop, but it still mutates the authoritative set, so it stays OFF unless explicitly enabled.
    if (!mSelfGrowEnabled.load(std::memory_order_relaxed) || validQuery.empty()) return;

    // pnpMatches, not `matches`: that name used to be the knnMatch result vector in this scope, and
    // although Phase 4 moved it inside the global-fallback branch the reason to keep them apart
    // stands — one counts descriptor correspondences, the other counts the PnP's.
    cv::Matx33d R; cv::Vec3d t; double fx, fy, cx, cy; int inliers; int pnpMatches; long seq;
    float spread = kPromotionNotMeasured; bool trusted = false;
    std::vector<cv::Point3f> wall;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        seq = mPnpResultSeq.load(std::memory_order_relaxed);
        if (seq == mLastGrowSeq) return;          // no fresh relock this tick — pose would be stale
        inliers = mPnpInlierCount.load(std::memory_order_relaxed);
        pnpMatches = mPnpMatchCount.load(std::memory_order_relaxed);
        const float* M = mPnpCamFromFpWorld;      // camera_from_fpWorld, column-major (OpenCV frame)
        R = cv::Matx33d(M[0], M[4], M[8], M[1], M[5], M[9], M[2], M[6], M[10]);
        t = cv::Vec3d(M[12], M[13], M[14]);
        fx = mFingerprintIntrinsics[0]; fy = mFingerprintIntrinsics[1];
        cx = mFingerprintIntrinsics[2]; cy = mFingerprintIntrinsics[3];
        wall = mWallKeypoints3D;
        // 3.2 — read ONCE and reuse, rather than evaluating the same predicate on both sides of the
        // lock as this did before. With a third input that moves per attempt, the reloc thread can
        // rewrite it between the two calls, which opens a window where the seq is claimed and the
        // promotion then refused — or, worse, the reverse.
        spread = mLastRelocInlierSpread.load(std::memory_order_relaxed);
        trusted = growTrusted(inliers, pnpMatches, spread);
        if (trusted) mLastGrowSeq = seq;          // claim it (yield or not)
    }
    if (!trusted || fx <= 0 || fy <= 0 ||
        wall.size() < 12 || wall.size() >= kMaxWallMarks) return;

    // Wall plane (n·X = pdist, pdist>0) in the fingerprint frame, fit to the existing marks.
    cv::Mat data((int)wall.size(), 3, CV_32F);
    for (int i = 0; i < (int)wall.size(); ++i) {
        data.at<float>(i,0) = wall[i].x; data.at<float>(i,1) = wall[i].y; data.at<float>(i,2) = wall[i].z;
    }
    cv::PCA pca(data, cv::Mat(), cv::PCA::DATA_AS_ROW);
    cv::Vec3d n(pca.eigenvectors.at<float>(2,0), pca.eigenvectors.at<float>(2,1), pca.eigenvectors.at<float>(2,2));
    cv::Vec3d cen(pca.mean.at<float>(0,0), pca.mean.at<float>(0,1), pca.mean.at<float>(0,2));
    double nn = cv::norm(n); if (nn < 1e-6) return; n /= nn;
    double pdist = n.dot(cen); if (pdist < 0) { n = -n; pdist = -pdist; } if (pdist < 1e-3) return;

    // fp_from_cam = [R^T | -R^T t]: camera centre and per-pixel ray in the fingerprint frame.
    cv::Matx33d Rt = R.t();
    cv::Vec3d C = -(Rt * t);
    double nDotC = n.dot(C);

    std::vector<cv::Point3f> newPts; cv::Mat newDescs;
    for (int q : validQuery) {
        if (q < 0 || q >= (int)kps.size()) continue;
        cv::Point2f p = kps[q].pt;
        cv::Vec3d dir = Rt * cv::Vec3d((p.x - cx) / fx, (p.y - cy) / fy, 1.0);
        double denom = n.dot(dir);
        if (std::abs(denom) < 1e-6) continue;
        double lambda = (pdist - nDotC) / denom;
        if (lambda <= 0) continue;                // intersection behind the camera
        cv::Vec3d X = C + lambda * dir;
        cv::Point3f Xp((float)X[0], (float)X[1], (float)X[2]);
        bool dup = false;
        for (const auto& w : wall)
            if (std::abs(w.x-Xp.x) < 0.01f && std::abs(w.y-Xp.y) < 0.01f && std::abs(w.z-Xp.z) < 0.01f) { dup = true; break; }
        if (dup) continue;
        newPts.push_back(Xp);
        newDescs.push_back(descs.row(q));
        if (newPts.size() >= 30) break;           // cap per relock
    }
    if (newPts.empty()) return;

    size_t promoted = 0, wallNow = 0;
    int outsideN = 0, insideN = 0, bandN = 0;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mWallDescriptors.type() != newDescs.type() || mWallDescriptors.cols != newDescs.cols ||
            mWallKeypoints3D.size() >= kMaxWallMarks) return;
        // Fill to the cap exactly. Testing the size only BEFORE the loop let a wall sitting at
        // kMaxWallMarks-1 grow by the full per-relock batch, so the documented ceiling was really
        // ceiling + batch.
        const size_t room = kMaxWallMarks - mWallKeypoints3D.size();
        const size_t take = std::min(room, newPts.size());
        // IMPLEMENTATION.md 3.4 — classify each promotion candidate with Φ, HERE, at promotion time.
        //
        // Until this landed every promoted mark was tagged BAND: correct as a refusal (an
        // unclassified feature must never join the backbone) but it meant self-grow could not
        // enlarge F_out at all, which is most of what self-grow is for. The placement Phase 4
        // pushed for the corroboration search is the same matrix Φ needs, so the classification is
        // now available at exactly the moment a point is being written into the authoritative map.
        //
        // No placement means no answer, and no answer still means BAND. That is not a fallback to
        // the old behaviour by accident — it is the same refusal, for the same reason.
        const bool canClassify = mHasDesignPlacement && mDesignHalfW > 0.0f && mDesignHalfH > 0.0f;
        int promotedOutside = 0, promotedInside = 0, promotedBand = 0;
        for (size_t i = 0; i < take; ++i) {
            mWallKeypoints3D.push_back(newPts[i]);
            mWallDescriptors.push_back(newDescs.row((int)i));
            // Keep the partition 1:1 with the points it indexes, or the reloc filter silently
            // switches itself off (its length check fails) and the whole map goes back to being
            // undifferentiated.
            if (!mWallRegions.empty()) {
                const uint8_t region = canClassify
                    ? classifyInFingerprintFrame(mDesignFpFromDesign, mDesignHalfW, mDesignHalfH,
                                                 newPts[i])
                    : kRegionBand;
                // 3.5 — an INSIDE promotion is wet paint: it sits under the artwork and is going to
                // change again as the artist works over it. Tagged INSIDE, which puts it in F_in,
                // where the reloc filter already refuses to see it and corroboration can. Whether
                // those should additionally EXPIRE is 3.5's open half, and IMPLEMENTATION.md is
                // explicit that it be decided from E5's result rather than in advance — so nothing
                // here invents a lifetime.
                if (region == kRegionOutside) ++promotedOutside;
                else if (region == kRegionInside) ++promotedInside;
                else ++promotedBand;
                mWallRegions.push_back(region);
            }
        }
        // Snapshot inside the lock: these feed a log line below, and reading the containers after
        // the guard released races a concurrent restoreWallFingerprintMetric on the JNI thread.
        promoted = take;
        wallNow = mWallKeypoints3D.size();
        outsideN = promotedOutside; insideN = promotedInside; bandN = promotedBand;
    }
    LOGI("Teleological self-grow: promoted %zu marks (wall now %zu; F_out +%d, F_in +%d, band +%d)",
         promoted, wallNow, outsideN, insideN, bandN);
}
void MobileGS::setArCoreTrackingState(bool t) { mIsArCoreTracking.store(t, std::memory_order_relaxed); }

void MobileGS::destroy() {
    mMapRunning = false;
    mRelocRunning = false;
    mQueueCv.notify_all();
    {
        std::lock_guard<std::mutex> lock(mRelocMutex);
        mRelocCv.notify_all();
    }
    if (mMapThread.joinable()) mMapThread.join();
    if (mRelocThread.joinable()) mRelocThread.join();
}

// Voxel/splat map deleted: .gxr keeps the fingerprint; old model.map files are ignored.
void MobileGS::saveModel(const std::string& p) {}
void MobileGS::loadModel(const std::string& p) {}
bool MobileGS::importModel3D(const std::string& p) { return false; }
void MobileGS::setViewportSize(int w, int h) { mScreenWidth = w; mScreenHeight = h; }
void MobileGS::setRelocEnabled(bool e) { mRelocEnabled = e; }

void MobileGS::setEvalRngSeed(long long seed) { mEvalRngSeed.store(seed, std::memory_order_relaxed); }

void MobileGS::setEvalSyncReloc(bool enabled, int everyN) {
    // Floored at 1. Zero would divide by zero in the cadence test; negative would make `n % everyN`
    // never zero, so the mode would be "on" and silently never relocalize -- which on a replay looks
    // exactly like relocalization being broken, and would be blamed on whatever parameter the run
    // was varying.
    mEvalSyncEveryN.store(std::max(1, everyN), std::memory_order_relaxed);
    // Reset on the transition, not only on enable: two runs in one process must start their cadence
    // from the same phase, or "every 5th frame" means a different five frames per run and the
    // determinism this exists to provide is not there.
    mEvalSyncFrameCounter.store(0, std::memory_order_relaxed);
    mEvalSyncReloc.store(enabled, std::memory_order_relaxed);
    LOGI("Eval sync-reloc %s (every %d frames)", enabled ? "ON" : "off", std::max(1, everyN));
}
void MobileGS::restoreWallFingerprint(const cv::Mat& d, const std::vector<cv::Point3f>& p) {
    std::lock_guard<std::mutex> lock(mMutex);
    mWallDescriptors = d.clone();
    mWallKeypoints3D = p;
    // This path carries no partition, and the previous fingerprint's must not survive onto it: the
    // bytes would index a different point set entirely. Empty = all backbone, as before Phase 2.
    mWallRegions.clear();
}
void MobileGS::restoreWallFingerprintMetric(const cv::Mat& d, const std::vector<cv::Point3f>& p,
                                            const float* anchorMatrix16, const float* intrinsics4,
                                            const float* viewMatrix16,
                                            const std::vector<uint8_t>& regions) {
    std::lock_guard<std::mutex> lock(mMutex);
    mWallDescriptors = d.clone();
    mWallKeypoints3D = p;
    // Belt and braces over the JNI-side length check: a partition that does not index the points it
    // is stored beside is worse than no partition, and this is the last place it can be refused
    // before the reloc thread subscripts it. Empty = all backbone = pre-Phase-2 behaviour.
    mWallRegions = (regions.size() == p.size()) ? regions : std::vector<uint8_t>();
    if (anchorMatrix16) memcpy(mFingerprintAnchorMatrix, anchorMatrix16, 16 * sizeof(float));
    if (intrinsics4)    memcpy(mFingerprintIntrinsics, intrinsics4, 4 * sizeof(float));
    if (viewMatrix16) {
        memcpy(mFingerprintViewMatrix, viewMatrix16, 16 * sizeof(float));
        mHasFingerprintView = true; // enables plane-guided rectification at reloc time
    } else {
        // A fingerprint without a capture view must not inherit the previous one's — the rectifying
        // warp would be computed against the wrong frontal frame and inject bad correspondences.
        mHasFingerprintView = false;
    }
}

void MobileGS::clearWallFingerprint() {
    std::lock_guard<std::mutex> lock(mMutex);
    mWallDescriptors.release();
    mWallKeypoints3D.clear();
    mWallRegions.clear();
    // Back to the constructed defaults, so a later project can't inherit this one's co-registration.
    static const float kIdentity16[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    memcpy(mFingerprintAnchorMatrix, kIdentity16, 16 * sizeof(float));
    memset(mFingerprintIntrinsics, 0, 4 * sizeof(float));
    mHasFingerprintView = false;

    // The ARTWORK side has to go too. It used to survive, and there is no other path that clears it:
    // an un-fingerprinted project would then be validated against the PREVIOUS project's artwork by
    // tryUpdateFingerprint, publishing a meaningless painting progress that reaches the user's
    // progress readout AND PoseFusion's correction strength — and, with self-grow enabled, promoting
    // this project's features into its map on the strength of a different project's target.
    mArtworkDescriptors.release();
    mArtworkKeypoints3D.clear();
    mArtworkKeypoints2D.clear();
    mArtworkCorroborated.clear();
    ++mArtworkGeneration;
    mArtworkImageW = 0;
    mArtworkImageH = 0;
    // The design's placement belongs to the project that placed it. Left behind, the corroboration
    // match would predict the next project's design features at the previous design's location on
    // the wall — a gated search that is confidently, precisely looking in the wrong place, which
    // reads downstream as "the wall does not corroborate" rather than as a stale-state bug.
    mHasDesignPlacement = false;
    mDesignHalfW = 0.0f;
    mDesignHalfH = 0.0f;
    mWallPatch.release();
    mPaintingProgress.store(0.0f, std::memory_order_relaxed);
    // Back to "never measured", not to zero — the next project has not been looked at yet, and
    // reporting a confident 0 would be a measurement it never made.
    mCorroborationConfidence.store(kCorroborationUnmeasured, std::memory_order_relaxed);
    mCorrobPredicted.store(-1, std::memory_order_relaxed);
    mCorrobMatched.store(-1, std::memory_order_relaxed);
    mCorrobLoneSkips.store(-1, std::memory_order_relaxed);
    mCorrobSearchRadiusPx.store(-1.0f, std::memory_order_relaxed);
    mLastGrowSeq = 0;
}

// IMPLEMENTATION.md 4.5. Takes the composition Kotlin already built for the Phi partition rather
// than the two factors, deliberately: recomposing it here would be a second derivation of the same
// quantity in a file that cannot see whether the anchor it was relative to is still the live one.
void MobileGS::setDesignPlacement(const float* fpFromDesign16, float halfW, float halfH) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!fpFromDesign16 || !(halfW > 0.0f) || !(halfH > 0.0f) ||
        !std::isfinite(halfW) || !std::isfinite(halfH)) {
        // A refusal, not a guess. Without a usable placement the corroboration match falls back to
        // the global search — Phase 4 off, which is a state the code already handles correctly.
        mHasDesignPlacement = false;
        mDesignHalfW = 0.0f;
        mDesignHalfH = 0.0f;
        return;
    }
    for (int i = 0; i < 16; ++i) {
        if (!std::isfinite(fpFromDesign16[i])) { mHasDesignPlacement = false; return; }
    }
    memcpy(mDesignFpFromDesign, fpFromDesign16, 16 * sizeof(float));
    mDesignHalfW = halfW;
    mDesignHalfH = halfH;
    mHasDesignPlacement = true;
}

void MobileGS::restoreWallFeatureMap(const cv::Mat& d, const std::vector<cv::Point3f>& p,
                                     const std::vector<float>& conf, const std::vector<int>& obs,
                                     const float* anchorMatrix16, const float* intrinsics4) {
    std::lock_guard<std::mutex> lock(mMutex);
    mMapDescriptors = d.clone();
    mMapPoints3D = p;
    mMapConfidence = conf;
    mMapObs = obs;
    // Reset (not leave stale) when a map omits co-registration, so it can't inherit a previous
    // project's anchor/intrinsics.
    static const float kIdentity16[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    memcpy(mMapAnchorMatrix, anchorMatrix16 ? anchorMatrix16 : kIdentity16, 16 * sizeof(float));
    if (intrinsics4) memcpy(mMapIntrinsics, intrinsics4, 4 * sizeof(float));
    else             memset(mMapIntrinsics, 0, 4 * sizeof(float));
}

void MobileGS::clearWallFeatureMap() {
    std::lock_guard<std::mutex> lock(mMutex);
    mMapDescriptors.release();
    mMapPoints3D.clear();
    mMapConfidence.clear();
    mMapObs.clear();
    // Also drop stale co-registration so a later project can't inherit it.
    static const float kIdentity16[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    memcpy(mMapAnchorMatrix, kIdentity16, 16 * sizeof(float));
    memset(mMapIntrinsics, 0, 4 * sizeof(float));
}

std::vector<uint8_t> MobileGS::exportFingerprint() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mWallDescriptors.empty() || mWallKeypoints3D.empty()) return {};

    // NOTE: the co-op wire format carries no Phase-2 partition, deliberately. It is a fixed,
    // unversioned layout shared with peers that may be running an older build, so appending
    // mWallRegions here would be read as descriptor bytes on the other end. The receiving side
    // (alignToFingerprint) clears mWallRegions for the same reason, so a shared fingerprint is
    // all-backbone on arrival — degraded, but correct, and the peer partitions its own once it
    // places its own artwork. Adding a version field is what would let this change.
    uint32_t numPoints = static_cast<uint32_t>(mWallKeypoints3D.size());
    uint32_t descRows = static_cast<uint32_t>(mWallDescriptors.rows);
    uint32_t descCols = static_cast<uint32_t>(mWallDescriptors.cols);
    uint32_t descType = static_cast<uint32_t>(mWallDescriptors.type());
    size_t descDataSize = mWallDescriptors.total() * mWallDescriptors.elemSize();

    size_t totalSize = sizeof(uint32_t) * 4 +
                       numPoints * sizeof(cv::Point3f) +
                       descDataSize;

    std::vector<uint8_t> buffer(totalSize);
    uint8_t* ptr = buffer.data();

    memcpy(ptr, &numPoints, sizeof(uint32_t)); ptr += sizeof(uint32_t);
    memcpy(ptr, mWallKeypoints3D.data(), numPoints * sizeof(cv::Point3f)); ptr += numPoints * sizeof(cv::Point3f);
    memcpy(ptr, &descRows, sizeof(uint32_t)); ptr += sizeof(uint32_t);
    memcpy(ptr, &descCols, sizeof(uint32_t)); ptr += sizeof(uint32_t);
    memcpy(ptr, &descType, sizeof(uint32_t)); ptr += sizeof(uint32_t);
    memcpy(ptr, mWallDescriptors.data, descDataSize);

    return buffer;
}

void MobileGS::alignToFingerprint(const uint8_t* data, size_t size) {
    // Untrusted peer bytes. Validate every length in 64-bit arithmetic (Android ships 32-bit ABIs
    // where numPoints * sizeof(Point3f) would wrap size_t) and BEFORE allocating any cv::Mat from
    // peer-controlled dimensions. Bail on anything inconsistent rather than crash the co-op session.
    if (!data || size < sizeof(uint32_t) * 4) return;

    const uint8_t* ptr = data;
    const uint8_t* end = data + size;

    uint32_t numPoints;
    memcpy(&numPoints, ptr, sizeof(uint32_t)); ptr += sizeof(uint32_t);

    // Cap up front: even though the bounds check below rejects a numPoints larger than the buffer,
    // an explicit ceiling documents the intent and refuses an absurd count before std::vector tries
    // to reserve it. A real wall fingerprint is a few thousand points.
    if (numPoints > 100000) return;

    // The points block plus the 3 trailing header ints (descRows/descCols/descType) must fit.
    uint64_t ptsBytes = static_cast<uint64_t>(numPoints) * sizeof(cv::Point3f);
    if (static_cast<uint64_t>(end - ptr) < ptsBytes + sizeof(uint32_t) * 3) return;

    std::vector<cv::Point3f> points3d(numPoints);
    if (numPoints > 0) memcpy(points3d.data(), ptr, static_cast<size_t>(ptsBytes));
    ptr += ptsBytes;

    uint32_t descRows, descCols, descType;
    memcpy(&descRows, ptr, sizeof(uint32_t)); ptr += sizeof(uint32_t);
    memcpy(&descCols, ptr, sizeof(uint32_t)); ptr += sizeof(uint32_t);
    memcpy(&descType, ptr, sizeof(uint32_t)); ptr += sizeof(uint32_t);

    // Sanity-check the descriptor header before it reaches cv::Mat: a bogus type or absurd dims from a
    // hostile peer would otherwise throw inside cv::Mat or attempt a multi-GB allocation.
    int depth = CV_MAT_DEPTH(descType);
    int channels = CV_MAT_CN(descType);
    if (depth < 0 || depth > CV_64F || channels < 1 || channels > 4) return;
    if (descRows == 0 || descCols == 0 || descRows > 100000 || descCols > 100000) return;

    uint64_t descDataSize = static_cast<uint64_t>(descRows) * descCols * CV_ELEM_SIZE(descType);
    if (static_cast<uint64_t>(end - ptr) < descDataSize) return;

    cv::Mat descs(static_cast<int>(descRows), static_cast<int>(descCols), static_cast<int>(descType));
    memcpy(descs.data, ptr, static_cast<size_t>(descDataSize));

    {
        std::lock_guard<std::mutex> lock(mMutex);
        mWallKeypoints3D = std::move(points3d);
        mWallDescriptors = descs.clone();
        // A peer's fingerprint carries no partition, and the local one indexes a different point
        // set. Empty = all backbone, i.e. pre-Phase-2 behaviour, which is the right default for a
        // map whose design footprint this device never saw.
        mWallRegions.clear();
        mRelocRequested = true; // Trigger relocalization thread to start searching
    }
    LOGI("Co-op: Received fingerprint with %u points. Relocalization triggered.", numPoints);
}
bool MobileGS::relocWantsFrame() {
    if (!mRelocEnabled) return false;
    // EVAL SYNC MODE deliberately does NOT filter by cadence here, and the cost of that is real:
    // mRelocRequested is never set in sync mode, so the throttle below always answers "yes" and the
    // caller pays a full-frame YUV->RGB conversion plus a rotate for every frame the every-N test is
    // about to discard.
    //
    // The obvious fix -- peek `(counter + 1) % everyN` here -- DEADLOCKS, and the way it does is
    // worth writing down because it looks correct. The counter advances only inside
    // scheduleRelocCheck, which the caller invokes only when this returns true. At everyN=5 the peek
    // sees 1, refuses, the counter never moves, and the peek sees 1 forever: sync mode is "on" and
    // never relocalizes. Moving the increment here instead would make scheduleRelocCheck's cadence
    // depend on this function having been called first -- a coupling across two files that the next
    // call site to appear would break silently.
    //
    // So the waste is accepted, and it is bounded: this is an eval affordance whose own comment
    // already says the inline cadence is not one a real device would choose. Correct cadence beats a
    // saved conversion on a path that exists to make measurements comparable.
    if (mRelocRequested) return false; // worker still holds the previous frame
    std::lock_guard<std::mutex> lock(mMutex);
    return !mWallDescriptors.empty();
}

void MobileGS::scheduleRelocCheck(const cv::Mat& f) {
    // Feed the latest camera frame to the background relocalization thread. Previously a no-op, which
    // meant mRelocColorFrame was never populated and the reloc thread always saw an empty frame —
    // live-camera PnP relocalization never ran. Throttles to the reloc thread's consume rate: while a
    // request is still pending we skip, so we only copy a frame when the worker is ready for the next.
    if (f.empty() || !mRelocEnabled) return;
    {
        // mWallDescriptors is reassigned under mMutex (generateFingerprint / restore paths /
        // self-grow); an unlocked empty() probe races those cv::Mat header writes. Scoped so it
        // never nests with mRelocMutex below.
        std::lock_guard<std::mutex> lock(mMutex);
        if (mWallDescriptors.empty()) return; // nothing to match against yet
    }
    // EVALUATION.md 3.1 — inline mode. Run the pass on THIS thread, one frame in N, and never set
    // mRelocRequested, so the background worker stays parked on its condition variable rather than
    // racing us for the same frame.
    //
    // The frame is NOT copied into mRelocColorFrame here. Copying it would publish it to a worker
    // that is not going to read it, and the pass takes the frame by reference anyway; the only thing
    // that shared buffer buys is the hand-off this mode exists to remove.
    if (mEvalSyncReloc.load(std::memory_order_relaxed)) {
        const int everyN = std::max(1, mEvalSyncEveryN.load(std::memory_order_relaxed));
        const long long n = mEvalSyncFrameCounter.fetch_add(1, std::memory_order_relaxed) + 1;
        if (n % everyN != 0) return;
        float view[16];
        {
            // Same snapshot the async path takes, under the same lock, so the rectifying warp sees
            // the view that goes with this frame in both modes. Running inline does not make an
            // unsynchronized read of mViewMatrix safe -- updateCamera is still another thread.
            std::lock_guard<std::mutex> lock(mMutex);
            memcpy(view, mViewMatrix, 16 * sizeof(float));
        }
        runRelocPass(f, view);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(mRelocMutex);
        if (mRelocRequested) return;
        f.copyTo(mRelocColorFrame);
        // Snapshot the latest VIO view alongside the frame so the rectifying warp matches it. A torn
        // read vs. updateCamera is harmless here — the warp is approximate and PnP refines it.
        memcpy(mRelocViewMatrix, mViewMatrix, 16 * sizeof(float));
        mRelocRequested = true;
    }
    mRelocCv.notify_one();
}

extern MobileGS* gSlamEngine;
namespace mobilegs {
    std::vector<uint8_t> exportFingerprint() {
        if (gSlamEngine) return gSlamEngine->exportFingerprint();
        return {};
    }
    void alignToFingerprint(const uint8_t* data, size_t size) {
        if (gSlamEngine) gSlamEngine->alignToFingerprint(data, size);
    }
}

bool MobileGS::loadSuperPoint(const std::vector<uchar>& onnxBytes) { return mSuperPoint.load(onnxBytes); }
bool MobileGS::loadLowLightEnhancer(const std::vector<uchar>& onnxBytes) { return mEnhancer.load(onnxBytes); }
// Teleological SLAM, stage 1: store the TARGET artwork as the validator reference. Its features +
// metric 3D describe "what the wall should become"; tryUpdateFingerprint (stage 2) uses them to decide
// which new real paint-marks to promote into the live fingerprint as the original marks get covered.
// Mirrors generateFingerprint's detect + depth back-projection, stored into mArtwork* (no mask: the
// whole target is the reference).
void MobileGS::setArtworkFingerprint(const cv::Mat& composite, const uint8_t* depthData,
                                     int depthW, int depthH, int depthStride,
                                     const float* intr, const float* /*viewMat*/) {
    if (composite.empty()) {
        LOGE("setArtworkFingerprint: empty composite");
        return;
    }

    cv::Mat gray;
    if (composite.channels() == 4)      cv::cvtColor(composite, gray, cv::COLOR_RGBA2GRAY);
    else if (composite.channels() == 3) cv::cvtColor(composite, gray, cv::COLOR_RGB2GRAY);
    else                                gray = composite;
    normalizeForFeatures(gray); // match the live frame's illumination normalization

    // Detect with the SAME descriptor type as the wall fingerprint so the gatekeeper match (clean-vs-
    // artwork) and self-grow promotion are type-compatible: if the wall is ORB (CV_8U, the depth-off
    // path) use ORB here too; otherwise SuperPoint. Without this, an ORB wall + SuperPoint artwork can't
    // match and painting-progress/self-grow silently do nothing in the depth-off config.
    bool wallIsOrb;
    { std::lock_guard<std::mutex> lock(mMutex); wallIsOrb = !mWallDescriptors.empty() && mWallDescriptors.type() == CV_8U; }

    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;
    bool useSuperPoint = mSuperPoint.isLoaded() && !wallIsOrb;
    if (useSuperPoint && !mSuperPoint.detect(gray, kps, descs)) useSuperPoint = false;
    if (!useSuperPoint || kps.empty()) {
        auto orb = cv::ORB::create(1500);
        orb->detectAndCompute(gray, cv::noArray(), kps, descs);
    }
    if (kps.empty() || descs.empty()) {
        LOGE("setArtworkFingerprint: no keypoints detected on target");
        return;
    }

    // 3D from the capture depth when available (enables the staged self-grow promotion). With the ML
    // depth API off — the option-A path registers the design composite, which has NO depth — store
    // descriptors-only; that is enough to drive painting-progress. Previously this required depth and
    // bailed on the option-A path, so painting-progress never registered.
    std::vector<cv::Point3f> pts3d;
    cv::Mat keepDescs = descs;
    // IMPLEMENTATION.md 4.5 — kept in lockstep with keepDescs, INCLUDING through the depth filter
    // below. The filter rebuilds the descriptor matrix from a subset of rows; a 2D list built before
    // it and stored afterwards would be indexed by descriptor row and silently return a different
    // feature's pixel, which projects to a plausible place and corroborates the wrong thing.
    std::vector<cv::Point2f> keepPts2d;
    keepPts2d.reserve(kps.size());
    for (const auto& kp : kps) keepPts2d.push_back(kp.pt);
    if (depthData && depthW > 0 && depthH > 0 && depthStride > 0 && intr) {
        const float fx = intr[0], fy = intr[1], cx = intr[2], cy = intr[3];
        const float scaleX = (float)depthW / (float)composite.cols;
        const float scaleY = (float)depthH / (float)composite.rows;
        std::vector<int> validIdx;
        for (int idx = 0; idx < (int)kps.size(); ++idx) {
            const auto& kp = kps[idx];
            int dx = std::max(0, std::min((int)std::round(kp.pt.x * scaleX), depthW - 1));
            int dy = std::max(0, std::min((int)std::round(kp.pt.y * scaleY), depthH - 1));
            const auto* row = reinterpret_cast<const uint16_t*>(depthData + (size_t)dy * depthStride);
            float depthMm = (float)(row[dx] & 0x1FFF);
            if (depthMm < 100.0f) continue; // missing / too close
            float Z = depthMm / 1000.0f;
            pts3d.emplace_back((kp.pt.x - cx) / fx * Z, (kp.pt.y - cy) / fy * Z, Z);
            validIdx.push_back(idx);
        }
        if (!validIdx.empty()) {
            cv::Mat validDescs((int)validIdx.size(), descs.cols, descs.type());
            std::vector<cv::Point2f> validPts2d;
            validPts2d.reserve(validIdx.size());
            for (int k = 0; k < (int)validIdx.size(); ++k) {
                descs.row(validIdx[k]).copyTo(validDescs.row(k));
                validPts2d.push_back(kps[(size_t)validIdx[k]].pt);
            }
            keepDescs = validDescs;   // keep descriptors aligned 1:1 with pts3d
            keepPts2d = std::move(validPts2d);
        }
    }

    int storedRows = 0; size_t storedPts = 0;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        mArtworkDescriptors = keepDescs.clone();
        mArtworkKeypoints3D = std::move(pts3d);
        mArtworkKeypoints2D = std::move(keepPts2d);
        mArtworkImageW = gray.cols;
        mArtworkImageH = gray.rows;
        // A new design means nothing corroborated so far corroborates THIS one.
        mArtworkCorroborated.assign((size_t)mArtworkDescriptors.rows, 0);
        ++mArtworkGeneration;
        mCorrobPredicted.store(-1, std::memory_order_relaxed);
        mCorrobMatched.store(-1, std::memory_order_relaxed);
        mCorrobLoneSkips.store(-1, std::memory_order_relaxed);
        mCorrobSearchRadiusPx.store(-1.0f, std::memory_order_relaxed);
        mPaintingProgress.store(0.0f, std::memory_order_relaxed);
        // A new design means every prior corroboration reading was against a different target.
        mCorroborationConfidence.store(kCorroborationUnmeasured, std::memory_order_relaxed);
        // Snapshot under the lock — the reloc thread reads both of these, so logging them after the
        // guard releases is a race on a cv::Mat header and a vector.
        storedRows = mArtworkDescriptors.rows;
        storedPts = mArtworkKeypoints3D.size();
    }
    LOGI("setArtworkFingerprint: stored %d validator features (%zu with 3D)", storedRows, storedPts);
}

void MobileGS::setWallPatch(const cv::Mat& img) {
    if (img.empty()) return;
    cv::Mat gray;
    if (img.channels() == 4)      cv::cvtColor(img, gray, cv::COLOR_RGBA2GRAY);
    else if (img.channels() == 3) cv::cvtColor(img, gray, cv::COLOR_RGB2GRAY);
    else                          gray = img;
    cv::Mat patch;
    cv::resize(gray, patch, cv::Size(DistortionHead::kPatch, DistortionHead::kPatch));
    std::lock_guard<std::mutex> lock(mMutex);
    mWallPatch = patch.clone(); // raw gray, no CLAHE (the head's SuperPoint was trained on raw gray)
}

bool MobileGS::getSuperPointFeatures(const cv::Mat& image, std::vector<cv::KeyPoint>& kps, cv::Mat& descs) {
    if (!mSuperPoint.isLoaded() || image.empty()) return false;
    cv::Mat gray;
    if (image.channels() == 4)      cv::cvtColor(image, gray, cv::COLOR_RGBA2GRAY);
    else if (image.channels() == 3) cv::cvtColor(image, gray, cv::COLOR_RGB2GRAY);
    else                            gray = image;
    normalizeForFeatures(gray); // CLAHE, identical to the reloc path so descriptors stay comparable
    if (!mSuperPoint.detect(gray, kps, descs)) return false;
    return !kps.empty() && !descs.empty();
}

void MobileGS::getFingerprintKeypoints(const cv::Mat& image, const cv::Mat& mask,
                                       std::vector<cv::Point2f>& out) {
    out.clear();
    if (image.empty()) return;

    // Mirror generateFingerprint's detection so the overlay shows the REAL fingerprint features (not a
    // different ORB config): low-light enhance, grayscale, the marks mask, SuperPoint then ORB-1000.
    cv::Mat workFrame = image;
    if (mEnhancer.isLoaded() && mLightLevel < kLowLightThreshold) {
        cv::Mat enhanced; if (mEnhancer.enhance(image, enhanced)) workFrame = enhanced;
    }
    cv::Mat gray;
    if (workFrame.channels() == 4)      cv::cvtColor(workFrame, gray, cv::COLOR_RGBA2GRAY);
    else if (workFrame.channels() == 3) cv::cvtColor(workFrame, gray, cv::COLOR_RGB2GRAY);
    else                                gray = workFrame;
    normalizeForFeatures(gray); // same normalization as generateFingerprint, so the overlay is truthful

    cv::Mat orbMask;
    if (!mask.empty()) {
        if (mask.channels() == 4) {
            std::vector<cv::Mat> ch; cv::split(mask, ch); orbMask = ch[3];
        } else {
            cv::Mat s; if (mask.channels() == 3) cv::cvtColor(mask, s, cv::COLOR_RGB2GRAY); else s = mask;
            cv::threshold(s, orbMask, 1, 255, cv::THRESH_BINARY);
        }
    }

    std::vector<cv::KeyPoint> kps; cv::Mat descs;
    bool sp = mSuperPoint.isLoaded();
    if (sp && !mSuperPoint.detect(gray, kps, descs, orbMask)) sp = false;
    if (!sp || kps.empty()) { cv::ORB::create(1000)->detect(gray, kps, orbMask); }

    out.reserve(kps.size());
    for (const auto& k : kps) out.push_back(k.pt);
}

MobileGS::FingerprintData MobileGS::generateFingerprint(
        const cv::Mat& image, const cv::Mat& mask,
        const uint8_t* depthData, int depthW, int depthH, int depthStride,
        const float* intr, const float* viewMat)
{
    if (image.empty()) return {};

    // Optionally enhance the RGB frame under low light before grayscale conversion
    cv::Mat workFrame = image;
    if (mEnhancer.isLoaded() && mLightLevel < kLowLightThreshold) {
        cv::Mat enhanced;
        if (mEnhancer.enhance(image, enhanced)) workFrame = enhanced;
    }

    cv::Mat gray;
    if (workFrame.channels() == 4)
        cv::cvtColor(workFrame, gray, cv::COLOR_RGBA2GRAY);
    else if (workFrame.channels() == 3)
        cv::cvtColor(workFrame, gray, cv::COLOR_RGB2GRAY);
    else
        gray = workFrame;
    normalizeForFeatures(gray); // illumination-normalize to match the live reloc frame

    cv::Mat orbMask;
    if (!mask.empty()) {
        if (mask.channels() == 4) {
            // isolateMarkings() produces a bitmap where markings are OPAQUE (alpha 255)
            // and background is TRANSPARENT (alpha 0).
            std::vector<cv::Mat> channels;
            cv::split(mask, channels);
            orbMask = channels[3];
        } else {
            cv::Mat singleCh;
            if (mask.channels() == 3)
                cv::cvtColor(mask, singleCh, cv::COLOR_RGB2GRAY);
            else
                singleCh = mask;
            cv::threshold(singleCh, orbMask, 1, 255, cv::THRESH_BINARY);
        }
    }

    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;

    // SuperPoint detection with fallback to ORB
    bool useSuperPoint = mSuperPoint.isLoaded();
    if (useSuperPoint && !mSuperPoint.detect(gray, kps, descs, orbMask)) {
        useSuperPoint = false;
    }

    if (!useSuperPoint || kps.empty()) {
        auto orb = cv::ORB::create(1000);
        orb->detectAndCompute(gray, orbMask, kps, descs);
    }

    if (kps.empty() || descs.empty()) {
        LOGE("generateFingerprint: no keypoints detected");
        return {};
    }

    if (!depthData || depthW <= 0 || depthH <= 0 || depthStride <= 0) {
        FingerprintData fd;
        fd.keypoints = kps;
        fd.descriptors = descs.clone();
        return fd;
    }

    float fx = intr[0], fy = intr[1], cx = intr[2], cy = intr[3];
    float scaleX = (float)depthW  / (float)image.cols;
    float scaleY = (float)depthH  / (float)image.rows;

    std::vector<cv::KeyPoint>  validKps;
    std::vector<cv::Point3f>   pts3d;
    std::vector<int>           validIdx;

    int tooClose = 0, tooFar = 0, missing = 0;

    for (int i = 0; i < (int)kps.size(); ++i) {
        const auto& kp = kps[i];
        int dx = std::max(0, std::min((int)std::round(kp.pt.x * scaleX), depthW - 1));
        int dy = std::max(0, std::min((int)std::round(kp.pt.y * scaleY), depthH - 1));

        const auto* row = reinterpret_cast<const uint16_t*>(depthData + (size_t)dy * depthStride);
        uint16_t val = row[dx];
        float depthMm = (float)(val & 0x1FFF);

        if (depthMm == 0) { missing++; continue; }
        if (depthMm < 100.0f) { tooClose++; continue; }

        float Z = depthMm / 1000.0f;
        float X = (kp.pt.x - cx) / fx * Z;
        float Y = (kp.pt.y - cy) / fy * Z;

        validKps.push_back(kp);
        pts3d.emplace_back(X, Y, Z);
        validIdx.push_back(i);
    }

    LOGI("generateFingerprint: %zu/%zu keypoints have valid depth (scaleX=%.4f, scaleY=%.4f, depthW=%d, depthH=%d)",
         validKps.size(), kps.size(), scaleX, scaleY, depthW, depthH);
    if (validKps.empty()) {
        LOGE("generateFingerprint: no valid depth. Counts: tooClose=%d, tooFar=%d, missing=%d. Total kps=%zu",
             tooClose, tooFar, missing, kps.size());
        return {};
    }

    // Build aligned descriptor matrix (rows matching validKps only)
    cv::Mat validDescs((int)validIdx.size(), descs.cols, descs.type());
    for (int i = 0; i < (int)validIdx.size(); ++i)
        descs.row(validIdx[i]).copyTo(validDescs.row(i));

    std::vector<float> pts3dFlat;
    pts3dFlat.reserve(pts3d.size() * 3);
    for (const auto& p : pts3d) {
        pts3dFlat.push_back(p.x);
        pts3dFlat.push_back(p.y);
        pts3dFlat.push_back(p.z);
    }

    FingerprintData fd;
    fd.keypoints   = validKps;
    fd.points3d    = std::move(pts3dFlat);
    fd.descriptors = validDescs.clone();

    {
        std::lock_guard<std::mutex> lock(mMutex);
        mWallDescriptors  = fd.descriptors.clone();
        mWallKeypoints3D  = std::move(pts3d);
        // The depth path supplies no partition. Clearing rather than leaving the previous
        // fingerprint's is not optional: those bytes index a point set that no longer exists.
        mWallRegions.clear();
        memcpy(mFingerprintAnchorMatrix, mAnchorMatrix, 16 * sizeof(float));
        memcpy(mFingerprintIntrinsics, intr, 4 * sizeof(float));
        if (viewMat) {
            memcpy(mFingerprintViewMatrix, viewMat, 16 * sizeof(float));
            mHasFingerprintView = true; // enables plane-guided rectification at reloc time
        }
    }

    return fd;
}
void MobileGS::getStageTimingsAndReset(float* out) {
    for (int i = 0; i < kStageCount; ++i) {
        uint64_t n = mStageSamples[i].exchange(0, std::memory_order_relaxed);
        double acc = mStageAccumMs[i].exchange(0.0, std::memory_order_relaxed);
        out[i] = (n > 0) ? static_cast<float>(acc / static_cast<double>(n)) : 0.0f;
    }
}

void MobileGS::setStageEnabled(int stage, bool enabled) {
    // Only stages 1 (voxelKeyframe) and 2 (surfaceMesh) are gateable for A/B cost attribution.
    // Stage 0 (voxelUpdate) is the relocalization backbone; stages 3 (draw) and 4 (pnpReloc) are
    // timing-only and always run — their cost is read from the timers, never toggled. Reject 0/3/4
    // so setStageEnabled(3/4,false) isn't a confusing silent no-op.
    if (stage == 1 || stage == 2) mStageEnabled[stage].store(enabled, std::memory_order_relaxed);
}

void MobileGS::getRelocResult(float* out19) const {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(out19, mPnpCamFromFpWorld, 16 * sizeof(float));
    out19[16] = (float) mPnpInlierCount.load(std::memory_order_relaxed);
    out19[17] = (float) mPnpMatchCount.load(std::memory_order_relaxed);
    out19[18] = (float) mPnpResultSeq.load(std::memory_order_relaxed);
}
void MobileGS::getFingerprintAnchor(float* out16) const {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(out16, mFingerprintAnchorMatrix, 16 * sizeof(float));
}
