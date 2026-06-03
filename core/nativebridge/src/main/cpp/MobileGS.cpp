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
    if (!mOptimizeRunning) {
        mOptimizeRunning = true;
        mOptimizeThread = std::thread(&MobileGS::optimizeThreadFunc, this);
    }
}

void MobileGS::initGl() {
    mVoxelHash.initGl();
    mSurfaceMesh.initGl();
}

void MobileGS::resetGlContext() {
    initGl();
}

void MobileGS::draw() {
    std::lock_guard<std::mutex> lock(mMutex);
    interpolateAnchorStep();
    if (!mCameraReady) return;

    glm::mat4 V = glm::make_mat4(mViewMatrix);
    glm::mat4 P = glm::make_mat4(mProjMatrix);
    glm::mat4 mvp = P * V;

    if (mSplatsVisible) {
        StageTimer _t(&mStageAccumMs[3], &mStageSamples[3]);
        if (mMuralMethod == 0) { // VOXEL_HASH
            mVoxelHash.draw(mvp, V, std::abs(mProjMatrix[5]) * (mScreenHeight / 2.0f), mScreenHeight);
        } else if (mMuralMethod == 1) { // SURFACE_MESH
            mSurfaceMesh.draw(mvp);
        } else if (mMuralMethod == 2) { // CLOUD_OFFSET
            mVoxelHash.draw(mvp, V, std::abs(mProjMatrix[5]) * (mScreenHeight / 2.0f), mScreenHeight);
        }
    }
}

void MobileGS::pushPointCloud(const std::vector<float>& points) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mCameraReady) return;
    mVoxelHash.addSparsePoints(points, mViewMatrix, mProjMatrix, 0.4f);
}

void MobileGS::processDepthFrame(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, const float* intrinsics, bool isYuv, float confidence) {
    bool isTrackingState = false;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (depth.empty() || color.empty() || !mCameraReady || mMappingPaused) return;
        isTrackingState = mIsArCoreTracking;
    }
    if (!isTrackingState) return;

    cv::Mat colorRGB;
    if (isYuv) cv::cvtColor(color, colorRGB, cv::COLOR_YUV2RGB_NV21);
    else colorRGB = color;

    // Universal Ingestion: Build the Voxel Map in ALL modes to enable Snap-Back relocalization.
    { StageTimer _t(&mStageAccumMs[0], &mStageSamples[0]);
      mVoxelHash.update(depth, colorRGB, viewMat, projMat, mVoxelSize, confidence); }

    if (mScanMode == 0) return; // Canvas mode only needs the voxel map for background recovery.

    mFrameCounter++;
    if (mMuralMethod == 0) { // VOXEL_HASH
        if (mStageEnabled[1].load(std::memory_order_relaxed) && mFrameCounter % 30 == 0) {
            StageTimer _t(&mStageAccumMs[1], &mStageSamples[1]);
            VoxelFrame kf;
            kf.depth = depth.clone(); kf.color = colorRGB.clone();
            memcpy(kf.viewMatrix, viewMat, 16 * sizeof(float));
            memcpy(kf.projMatrix, projMat, 16 * sizeof(float));
            mVoxelHash.addKeyframe(kf);
        }
    } else if (mMuralMethod == 1) { // SURFACE_MESH
        if (mStageEnabled[2].load(std::memory_order_relaxed)) {
            StageTimer _t(&mStageAccumMs[2], &mStageSamples[2]);
            mSurfaceMesh.update(depth, colorRGB, viewMat, projMat, mAnchorMatrix, mLightLevel);
        }
    } else if (mMuralMethod == 2) { // CLOUD_OFFSET
        // Cloud Offset mode leverages the mVoxelHash map updated above.
    }
}

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

void MobileGS::pushFrame(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, const float* intrinsics, bool isYuv, float confidence) {
    if (!mMapRunning) return;
    std::lock_guard<std::mutex> lock(mQueueMutex);
    if (mFrameQueue.size() >= 1) mFrameQueue.erase(mFrameQueue.begin()); // Low-latency queue
    FrameData data;
    data.depth = depth.clone(); data.color = color.clone(); data.isYuv = isYuv; data.confidence = confidence;
    memcpy(data.viewMatrix, viewMat, 16 * sizeof(float));
    memcpy(data.projMatrix, projMat, 16 * sizeof(float));
    if (intrinsics) { memcpy(data.intrinsics, intrinsics, 4 * sizeof(float)); data.hasIntrinsics = true; }
    else data.hasIntrinsics = false;
    mFrameQueue.push_back(std::move(data));
    mQueueCv.notify_one();
}

void MobileGS::clearMap() {
    std::lock_guard<std::mutex> lock(mMutex);
    mVoxelHash.clear();
    mSurfaceMesh.clear();
}

void MobileGS::pruneByConfidence(float threshold) {
    mVoxelHash.prune(threshold);
}

void MobileGS::setArScanMode(int mode) { mScanMode = mode; }
void MobileGS::setMuralMethod(int method) { mMuralMethod = method; }
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
    outVisible = mVoxelHash.getVisibleConfidenceAvg();
    outGlobal = mVoxelHash.getGlobalConfidenceAvg();
}

void MobileGS::updatePersistentMesh(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat) {
    mSurfaceMesh.update(depth, color, viewMat, projMat, mAnchorMatrix, mLightLevel);
}

void MobileGS::getPersistentMesh(std::vector<float>& outVertices, std::vector<float>& outWeights) {
    mSurfaceMesh.getMesh(outVertices, outWeights);
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

        if (frame.empty() || mWallDescriptors.empty() || mWallKeypoints3D.empty() || !mRelocEnabled) continue;

        // Optionally enhance the RGB frame under low light before grayscale conversion
        cv::Mat workFrame = frame;
        if (mEnhancer.isLoaded() && mLightLevel < kLowLightThreshold) {
            cv::Mat enhanced;
            if (mEnhancer.enhance(frame, enhanced)) workFrame = enhanced;
        }
        cv::Mat gray;
        cv::cvtColor(workFrame, gray, cv::COLOR_RGB2GRAY);

        // SuperPoint usable when loaded and the wall fingerprint is float-typed (or empty).
        const bool spOk = mSuperPoint.isLoaded() &&
            (mWallDescriptors.empty() || mWallDescriptors.type() == CV_32F);

        // Detect + Lowe-ratio match a gray image against the wall fingerprint. When Hback is non-empty
        // the matched keypoints are mapped through it (rectified frame -> current image) before being
        // stored, so the returned 2D points are ALWAYS in the current camera image — exactly what the
        // PnP below expects.
        auto buildCorr = [&](const cv::Mat& g, const cv::Mat& Hback,
                             std::vector<cv::Point2f>& outImg, std::vector<cv::Point3f>& outObj) {
            std::vector<cv::KeyPoint> kps; cv::Mat descs;
            bool sp = spOk;
            if (sp && !mSuperPoint.detect(g, kps, descs)) sp = false;
            if (!sp) mFeatureDetector->detectAndCompute(g, cv::noArray(), kps, descs);
            if (descs.empty()) return;
            cv::Ptr<cv::DescriptorMatcher>& matcher = (descs.type() == CV_32F) ? mL2Matcher : mMatcher;
            std::vector<std::vector<cv::DMatch>> matches;
            matcher->knnMatch(descs, mWallDescriptors, matches, 2);
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
                    outObj.push_back(mWallKeypoints3D[match[0].trainIdx]);
                }
            }
        };

        std::vector<cv::Point2f> imgPts;
        std::vector<cv::Point3f> objPts;
        buildCorr(gray, cv::Mat(), imgPts, objPts);

        // Plane-guided rectification (perspective-robust matching). The marks lie on a known plane and
        // VIO gives a pose, so the oblique-vs-frontal distortion is a homography we can pre-cancel:
        // warp the live frame into the fingerprint's frontal frame and re-match there, recovering
        // oblique/partial relocks that raw descriptor matching loses. Strictly never-worse — only adopt
        // the rectified correspondences when they out-number the plain ones.
        if (mHasFingerprintView && mIsArCoreTracking && mWallKeypoints3D.size() >= 12) {
            cv::Mat Hcur_fp, Hfp_cur; double obliqDeg = 0.0;
            if (computeRectifyHomography(relocView, Hcur_fp, Hfp_cur, obliqDeg) && obliqDeg > 25.0) {
                cv::Mat grayRect;
                cv::warpPerspective(gray, grayRect, Hfp_cur, gray.size());
                std::vector<cv::Point2f> rImg; std::vector<cv::Point3f> rObj;
                buildCorr(grayRect, Hcur_fp, rImg, rObj);
                if (rImg.size() > imgPts.size()) {
                    LOGI("Reloc: rectified match (obliquity %.0f deg) %zu corr beats plain %zu",
                         obliqDeg, rImg.size(), imgPts.size());
                    imgPts.swap(rImg); objPts.swap(rObj);
                }
            }
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
            if (mFingerprintIntrinsics[0] > 0.0f && mFingerprintIntrinsics[1] > 0.0f) {
                fx = mFingerprintIntrinsics[0]; fy = mFingerprintIntrinsics[1];
                cx = mFingerprintIntrinsics[2]; cy = mFingerprintIntrinsics[3];
            }
            cv::Mat intr = (cv::Mat_<double>(3,3) << fx, 0, cx, 0, fy, cy, 0, 0, 1);
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
                }
            }
        }

        // Teleological gatekeeper: update painting-progress from how much of the artwork base the clean
        // frame now corroborates. No-op until an artwork is registered; read-only on the reloc set.
        tryUpdateFingerprint(gray);

        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
}
void MobileGS::runPnPMatch(const cv::Mat& frame) {}

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
    int matched = 0;
    for (auto& m : matches) {
        if (m.size() < 2) continue;
        if (m[0].distance < 0.75f * m[1].distance) {
            int a = m[0].trainIdx;
            if (a >= 0 && a < (int)hit.size() && !hit[a]) { hit[a] = 1; matched++; }
        }
    }
    if (artDescs.rows > 0)
        mPaintingProgress.store((float)matched / (float)artDescs.rows, std::memory_order_relaxed);
}
void MobileGS::interpolateAnchorStep() {}
void MobileGS::setArCoreTrackingState(bool t) { mIsArCoreTracking = t; }

void MobileGS::optimizeThreadFunc() {
    setpriority(PRIO_PROCESS, 0, 19);
    JniThreadAttacher attacher;
    while (mOptimizeRunning) {
        FrameData latestFrame;
        bool hasFrame = false;
        {
            std::lock_guard<std::mutex> lock(mQueueMutex);
            if (!mFrameQueue.empty()) { latestFrame = mFrameQueue.back(); hasFrame = true; }
        }
        if (hasFrame) {
            cv::Mat colorRGB;
            if (latestFrame.isYuv) cv::cvtColor(latestFrame.color, colorRGB, cv::COLOR_YUV2RGB_NV21);
            else colorRGB = latestFrame.color;
            mVoxelHash.optimize(latestFrame.depth, colorRGB, latestFrame.viewMatrix, latestFrame.projMatrix);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
}

void MobileGS::destroy() {
    mMapRunning = false;
    mOptimizeRunning = false;
    mRelocRunning = false;
    mSortRunning = false;
    mQueueCv.notify_all();
    {
        std::lock_guard<std::mutex> lock(mRelocMutex);
        mRelocCv.notify_all();
    }
    if (mMapThread.joinable()) mMapThread.join();
    if (mOptimizeThread.joinable()) mOptimizeThread.join();
    if (mRelocThread.joinable()) mRelocThread.join();
    if (mSortThread.joinable()) mSortThread.join();
}

void MobileGS::saveModel(const std::string& p) {
    mVoxelHash.save(p);
    mSurfaceMesh.save(p + ".mesh");
}
void MobileGS::loadModel(const std::string& p) {
    mVoxelHash.load(p);
    mSurfaceMesh.load(p + ".mesh");
}
bool MobileGS::importModel3D(const std::string& p) { return false; }
void MobileGS::setViewportSize(int w, int h) { mScreenWidth = w; mScreenHeight = h; }
void MobileGS::setRelocEnabled(bool e) { mRelocEnabled = e; }
void MobileGS::restoreWallFingerprint(const cv::Mat& d, const std::vector<cv::Point3f>& p) { mWallDescriptors = d.clone(); mWallKeypoints3D = p; }
void MobileGS::restoreWallFingerprintMetric(const cv::Mat& d, const std::vector<cv::Point3f>& p,
                                            const float* anchorMatrix16, const float* intrinsics4) {
    std::lock_guard<std::mutex> lock(mMutex);
    mWallDescriptors = d.clone();
    mWallKeypoints3D = p;
    if (anchorMatrix16) memcpy(mFingerprintAnchorMatrix, anchorMatrix16, 16 * sizeof(float));
    if (intrinsics4)    memcpy(mFingerprintIntrinsics, intrinsics4, 4 * sizeof(float));
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
    if (composite.empty() || !depthData || depthW <= 0 || depthH <= 0 || depthStride <= 0 || !intr) {
        LOGE("setArtworkFingerprint: invalid inputs");
        return;
    }

    cv::Mat gray;
    if (composite.channels() == 4)      cv::cvtColor(composite, gray, cv::COLOR_RGBA2GRAY);
    else if (composite.channels() == 3) cv::cvtColor(composite, gray, cv::COLOR_RGB2GRAY);
    else                                gray = composite;

    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;
    bool useSuperPoint = mSuperPoint.isLoaded();
    if (useSuperPoint && !mSuperPoint.detect(gray, kps, descs)) useSuperPoint = false;
    if (!useSuperPoint || kps.empty()) {
        auto orb = cv::ORB::create(1500);
        orb->detectAndCompute(gray, cv::noArray(), kps, descs);
    }
    if (kps.empty() || descs.empty()) {
        LOGE("setArtworkFingerprint: no keypoints detected on target");
        return;
    }

    const float fx = intr[0], fy = intr[1], cx = intr[2], cy = intr[3];
    const float scaleX = (float)depthW / (float)composite.cols;
    const float scaleY = (float)depthH / (float)composite.rows;

    std::vector<cv::Point3f> pts3d;
    std::vector<int> validIdx;
    for (int idx = 0; idx < (int)kps.size(); ++idx) {
        const auto& kp = kps[idx];
        int dx = std::max(0, std::min((int)std::round(kp.pt.x * scaleX), depthW - 1));
        int dy = std::max(0, std::min((int)std::round(kp.pt.y * scaleY), depthH - 1));
        const auto* row = reinterpret_cast<const uint16_t*>(depthData + (size_t)dy * depthStride);
        float depthMm = (float)(row[dx] & 0x1FFF);
        if (depthMm < 100.0f) continue; // missing / too close
        float Z = depthMm / 1000.0f;
        float X = (kp.pt.x - cx) / fx * Z;
        float Y = (kp.pt.y - cy) / fy * Z;
        pts3d.emplace_back(X, Y, Z);
        validIdx.push_back(idx);
    }
    if (pts3d.empty()) {
        LOGE("setArtworkFingerprint: no target keypoints had valid depth");
        return;
    }

    cv::Mat validDescs((int)validIdx.size(), descs.cols, descs.type());
    for (int k = 0; k < (int)validIdx.size(); ++k)
        descs.row(validIdx[k]).copyTo(validDescs.row(k));

    {
        std::lock_guard<std::mutex> lock(mMutex);
        mArtworkDescriptors = validDescs.clone();
        mArtworkKeypoints3D = std::move(pts3d);
        mPaintingProgress.store(0.0f, std::memory_order_relaxed);
    }
    LOGI("setArtworkFingerprint: stored %zu target validator features", mArtworkKeypoints3D.size());
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
void MobileGS::sortThreadFunc() {}

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
