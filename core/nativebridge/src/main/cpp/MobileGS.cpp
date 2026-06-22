#include "include/MobileGS.h"
#include <jni.h>
#include <EGL/egl.h>
#include <algorithm>
#include <android/log.h>
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
    mFeatureDetector = cv::ORB::create(500);
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

void MobileGS::setArScanMode(int mode) { mScanMode = mode; }
void MobileGS::setMuralMethod(int method) { mMuralMethod = method; }
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

        cv::Mat wallDescs;
        std::vector<cv::Point3f> wallKps3d;
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
            wallPatch = mWallPatch.clone();
            memcpy(fpIntrinsics, mFingerprintIntrinsics, 4 * sizeof(float));
            hasFpView = mHasFingerprintView;
            mapDescs = mMapDescriptors.clone();
            mapKps3d = mMapPoints3D;
            memcpy(mapPriorPose, mPnpCamFromFpWorld, 16 * sizeof(float));
            mapPriorSeq = mPnpResultSeq.load(std::memory_order_relaxed);
        }

        if (frame.empty() || wallDescs.empty() || wallKps3d.empty() || !mRelocEnabled) continue;

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

        auto buildCorr = [&](const cv::Mat& g, const cv::Mat& Hback,
                             std::vector<cv::Point2f>& outImg, std::vector<cv::Point3f>& outObj,
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

            cv::Ptr<cv::DescriptorMatcher>& matcher = (descs.type() == CV_32F) ? mL2Matcher : mMatcher;
            std::vector<std::vector<cv::DMatch>> matches;
            matcher->knnMatch(descs, wallDescs, matches, 2);
            for (auto& match : matches) {
                if (match.size() < 2) continue;
                if (match[0].distance < 0.75f * match[1].distance) {
                    cv::Point2f p = kps[match[0].queryIdx].pt;
                    if (!Hback.empty()) {
                        std::vector<cv::Point2f> in{p}, outp;
                        cv::perspectiveTransform(in, outp, Hback);
                        p = outp[0];
                    }
                    outImg.push_back(p);
                    outObj.push_back(wallKps3d[match[0].trainIdx]);
                }
            }
        };

        std::vector<cv::Point2f> imgPts;
        std::vector<cv::Point3f> objPts;
        buildCorr(gray, cv::Mat(), imgPts, objPts, &baseKps, &baseDescs);

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
            buildCorr(scaled, Hback, imgPts, objPts);
        }

        // Plane-guided rectification (perspective robustness for oblique views). The marks lie on a
        // known plane and VIO gives a pose, so the oblique-vs-frontal distortion is a homography we can
        // pre-cancel: warp the live frame into the fingerprint's frontal frame, match, and ADD the
        // correspondences mapped back to the current image (RANSAC filters any that don't fit).
        if (hasFpView && mIsArCoreTracking.load(std::memory_order_relaxed) && wallKps3d.size() >= 12) {
            cv::Mat Hcur_fp, Hfp_cur; double obliqDeg = 0.0;
            if (computeRectifyHomography(relocView, Hcur_fp, Hfp_cur, obliqDeg) && obliqDeg > 25.0) {
                cv::Mat grayRect;
                cv::warpPerspective(gray, grayRect, Hfp_cur, gray.size());
                size_t before = imgPts.size();
                buildCorr(grayRect, Hcur_fp, imgPts, objPts);
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
                        if (m[0].distance < 0.75f * m[1].distance) {
                            imgPts.push_back(baseKps[m[0].queryIdx].pt);
                            objPts.push_back(mapKps3d[visible[m[0].trainIdx]]);
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
                    mPaintingProgress.store(coverage, std::memory_order_relaxed);
                } else {
                    float p = mPaintingProgress.load(std::memory_order_relaxed);
                    mPaintingProgress.store(p * 0.9f, std::memory_order_relaxed);
                }
                LOGI("DistortionHead: match %.2f coverage %.2f tilt %.0f log2scale %.2f",
                     matchability, coverage, dist[8], dist[9]);
            } else {
                float p = mPaintingProgress.load(std::memory_order_relaxed);
                mPaintingProgress.store(p * 0.9f, std::memory_order_relaxed);
            }
        } else if (mDistortionHead.isLoaded()) {
            float p = mPaintingProgress.load(std::memory_order_relaxed);
            mPaintingProgress.store(p * 0.9f, std::memory_order_relaxed);
        }

        // Lowered floors so a close-up PARTIAL view (only a corner of the marks visible) can still
        // localize: PnP needs only a handful of correspondences. The inlier RATIO (published below) is
        // the quality gate PoseFusion actually trusts, so being permissive here is safe.
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
            if (cv::solvePnPRansac(objPts, imgPts, intr, cv::Mat(), rvec, tvec, false, 100, 8.0, 0.99, inliers)) {
                if (inliers.size() >= 6) {
                    // Refine on the RANSAC inliers. The marks lie on the wall plane, so resolve the
                    // planar two-fold (flip) ambiguity with IPPE and keep whichever pose reprojects
                    // best — but only adopt it if it strictly beats the RANSAC pose, so a non-coplanar
                    // inlier set can never make relocalization worse.
                    {
                        std::vector<cv::Point3f> inObj; std::vector<cv::Point2f> inImg;
                        inObj.reserve(inliers.size()); inImg.reserve(inliers.size());
                        for (int idx : inliers) { inObj.push_back(objPts[idx]); inImg.push_back(imgPts[idx]); }
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
                    LOGI("Relocalization: PnP match published (%zu/%zu inliers)", inliers.size(), imgPts.size());
                    // Phase 3 passive build: grow the feature map from this locked frame (default OFF).
                    if (mMapBuildEnabled.load(std::memory_order_relaxed))
                        growMapFromReloc(pnpMat, baseKps, baseDescs, fx, fy, cx, cy);
                }
            }
        }

        // Teleological gatekeeper: update painting-progress from how much of the artwork base the clean
        // frame now corroborates. No-op until an artwork is registered; read-only on the reloc set.
        tryUpdateFingerprint(gray);

        std::this_thread::sleep_for(std::chrono::milliseconds(200));
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
            if (m[0].distance < 0.75f * m[1].distance) {
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

void MobileGS::tryUpdateFingerprint(const cv::Mat& grayClean) {
    cv::Mat artDescs;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mArtworkDescriptors.empty()) return;
        artDescs = mArtworkDescriptors;
    }
    if (grayClean.empty()) return;

    // Detect on the CLEAN frame (the real wall incl. any new paint — overlays are GL-only, never in
    // this CV frame) and match against the ARTWORK base: a clean feature corroborates the target only
    // if it matches what the artwork expects. This is the validator for the upcoming self-grow.
    std::vector<cv::KeyPoint> kps; cv::Mat descs;
    bool sp = mSuperPoint.isLoaded() && (artDescs.type() == CV_32F);
    if (sp && !mSuperPoint.detect(grayClean, kps, descs)) sp = false;
    if (!sp) mFeatureDetector->detectAndCompute(grayClean, cv::noArray(), kps, descs);
    if (descs.empty() || descs.type() != artDescs.type()) return;

    cv::Ptr<cv::DescriptorMatcher>& matcher = (descs.type() == CV_32F) ? mL2Matcher : mMatcher;
    std::vector<std::vector<cv::DMatch>> matches;
    matcher->knnMatch(descs, artDescs, matches, 2);

    std::vector<char> hit(artDescs.rows, 0);
    std::vector<int> validQuery;   // clean keypoints that corroborate the artwork (self-grow candidates)
    int matched = 0;
    for (auto& m : matches) {
        if (m.size() < 2) continue;
        if (m[0].distance < 0.75f * m[1].distance) {
            int a = m[0].trainIdx;
            if (a >= 0 && a < (int)hit.size() && !hit[a]) { hit[a] = 1; matched++; }
            validQuery.push_back(m[0].queryIdx);
        }
    }
    // The distortion head's coverage is the principled progress signal; only fall back to this
    // descriptor-corroboration ratio when the head isn't present.
    if (artDescs.rows > 0 && !mDistortionHead.isLoaded())
        mPaintingProgress.store((float)matched / (float)artDescs.rows, std::memory_order_relaxed);

    // --- Teleological self-grow (opt-in) ---------------------------------------------------------
    // Promote validated NEW marks into the live reloc fingerprint so it self-grows from real painting
    // that matches the target — surviving the original marks being painted over. Depth-free: each new
    // feature is placed on the wall plane via the current relocalized pose. Guarded hard (fresh +
    // confident relock, gatekeeper-validated, capped, deduped) and RANSAC in the reloc PnP is the
    // backstop, but it still mutates the authoritative set, so it stays OFF unless explicitly enabled.
    if (!mSelfGrowEnabled.load(std::memory_order_relaxed) || validQuery.empty()) return;

    cv::Matx33d R; cv::Vec3d t; double fx, fy, cx, cy; int inliers; long seq;
    std::vector<cv::Point3f> wall;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        seq = mPnpResultSeq.load(std::memory_order_relaxed);
        if (seq == mLastGrowSeq) return;          // no fresh relock this tick — pose would be stale
        inliers = mPnpInlierCount.load(std::memory_order_relaxed);
        const float* M = mPnpCamFromFpWorld;      // camera_from_fpWorld, column-major (OpenCV frame)
        R = cv::Matx33d(M[0], M[4], M[8], M[1], M[5], M[9], M[2], M[6], M[10]);
        t = cv::Vec3d(M[12], M[13], M[14]);
        fx = mFingerprintIntrinsics[0]; fy = mFingerprintIntrinsics[1];
        cx = mFingerprintIntrinsics[2]; cy = mFingerprintIntrinsics[3];
        wall = mWallKeypoints3D;
        if (inliers >= 20) mLastGrowSeq = seq;    // claim this relock (whether or not it yields points)
    }
    if (inliers < 20 || fx <= 0 || fy <= 0 || wall.size() < 12 || wall.size() > 5000) return;

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

    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mWallDescriptors.type() != newDescs.type() || mWallDescriptors.cols != newDescs.cols || mWallKeypoints3D.size() > 5000) return;
        for (int i = 0; i < (int)newPts.size(); ++i) {
            mWallKeypoints3D.push_back(newPts[i]);
            mWallDescriptors.push_back(newDescs.row(i));
        }
    }
    LOGI("Teleological self-grow: promoted %zu marks (wall now %zu)", newPts.size(), mWallKeypoints3D.size());
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
void MobileGS::restoreWallFingerprint(const cv::Mat& d, const std::vector<cv::Point3f>& p) {
    std::lock_guard<std::mutex> lock(mMutex);
    mWallDescriptors = d.clone();
    mWallKeypoints3D = p;
}
void MobileGS::restoreWallFingerprintMetric(const cv::Mat& d, const std::vector<cv::Point3f>& p,
                                            const float* anchorMatrix16, const float* intrinsics4) {
    std::lock_guard<std::mutex> lock(mMutex);
    mWallDescriptors = d.clone();
    mWallKeypoints3D = p;
    if (anchorMatrix16) memcpy(mFingerprintAnchorMatrix, anchorMatrix16, 16 * sizeof(float));
    if (intrinsics4)    memcpy(mFingerprintIntrinsics, intrinsics4, 4 * sizeof(float));
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
    if (!data || size < sizeof(uint32_t) * 4) return;

    const uint8_t* ptr = data;
    uint32_t numPoints;
    memcpy(&numPoints, ptr, sizeof(uint32_t)); ptr += sizeof(uint32_t);

    if (size < sizeof(uint32_t) * 4 + numPoints * sizeof(cv::Point3f)) return;

    std::vector<cv::Point3f> points3d(numPoints);
    memcpy(points3d.data(), ptr, numPoints * sizeof(cv::Point3f)); ptr += numPoints * sizeof(cv::Point3f);

    uint32_t descRows, descCols, descType;
    memcpy(&descRows, ptr, sizeof(uint32_t)); ptr += sizeof(uint32_t);
    memcpy(&descCols, ptr, sizeof(uint32_t)); ptr += sizeof(uint32_t);
    memcpy(&descType, ptr, sizeof(uint32_t)); ptr += sizeof(uint32_t);

    cv::Mat descs(descRows, descCols, descType);
    size_t descDataSize = descs.total() * descs.elemSize();
    if (ptr + descDataSize > data + size) return;
    memcpy(descs.data, ptr, descDataSize);

    {
        std::lock_guard<std::mutex> lock(mMutex);
        mWallKeypoints3D = std::move(points3d);
        mWallDescriptors = descs.clone();
        mRelocRequested = true; // Trigger relocalization thread to start searching
    }
    LOGI("Co-op: Received fingerprint with %u points. Relocalization triggered.", numPoints);
}
void MobileGS::scheduleRelocCheck(const cv::Mat& f) {
    // Feed the latest camera frame to the background relocalization thread. Previously a no-op, which
    // meant mRelocColorFrame was never populated and the reloc thread always saw an empty frame —
    // live-camera PnP relocalization never ran. Throttles to the reloc thread's consume rate: while a
    // request is still pending we skip, so we only copy a frame when the worker is ready for the next.
    if (f.empty() || !mRelocEnabled) return;
    if (mWallDescriptors.empty()) return; // nothing to match against yet
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
            for (int k = 0; k < (int)validIdx.size(); ++k)
                descs.row(validIdx[k]).copyTo(validDescs.row(k));
            keepDescs = validDescs;   // keep descriptors aligned 1:1 with pts3d
        }
    }

    {
        std::lock_guard<std::mutex> lock(mMutex);
        mArtworkDescriptors = keepDescs.clone();
        mArtworkKeypoints3D = std::move(pts3d);
        mPaintingProgress.store(0.0f, std::memory_order_relaxed);
    }
    LOGI("setArtworkFingerprint: stored %d validator features (%zu with 3D)",
         mArtworkDescriptors.rows, (size_t)mArtworkKeypoints3D.size());
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
