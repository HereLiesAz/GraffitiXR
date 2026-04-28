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
    mL2Matcher = cv::DescriptorMatcher::create("BruteForce-L2");

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
        if (mMuralMethod == 0) { // VOXEL_HASH
            mVoxelHash.draw(mvp, V, std::abs(mProjMatrix[5]) * (mScreenHeight / 2.0f), mScreenHeight);
        } else if (mMuralMethod == 1) { // SURFACE_MESH
            mSurfaceMesh.draw(mvp);
        } else if (mMuralMethod == 2) { // CLOUD_OFFSET
            // Use VoxelHash renderer as fallback for Point Cloud Offset visualization
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
    mVoxelHash.update(depth, colorRGB, viewMat, projMat, mVoxelSize, confidence);

    if (mScanMode == 0) return; // Canvas mode only needs the voxel map for background recovery.

    mFrameCounter++;
    if (mMuralMethod == 0) { // VOXEL_HASH
        if (mFrameCounter % 30 == 0) { // Throttled keyframes
            VoxelFrame kf;
            kf.depth = depth.clone(); kf.color = colorRGB.clone();
            memcpy(kf.viewMatrix, viewMat, 16 * sizeof(float));
            memcpy(kf.projMatrix, projMat, 16 * sizeof(float));
            mVoxelHash.addKeyframe(kf);
        }
    } else if (mMuralMethod == 1) { // SURFACE_MESH
        mSurfaceMesh.update(depth, colorRGB, viewMat, projMat, mAnchorMatrix, mLightLevel);
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
        {
            std::unique_lock<std::mutex> lock(mRelocMutex);
            mRelocCv.wait(lock, [this] { return mRelocRequested || !mRelocRunning; });
            if (!mRelocRunning) break;
            frame = mRelocColorFrame.clone();
            mRelocRequested = false;
        }

        if (frame.empty() || mWallDescriptors.empty() || !mRelocEnabled) continue;

        // Convert RGB frame to grayscale for SuperPoint
        cv::Mat gray;
        cv::cvtColor(frame, gray, cv::COLOR_RGB2GRAY);

        std::vector<cv::KeyPoint> kps;
        cv::Mat descs;
        // Use SuperPoint if loaded and wall fingerprint type matches (float) or is absent
        bool useSuperPoint = mSuperPoint.isLoaded() &&
            (mWallDescriptors.empty() || mWallDescriptors.type() == CV_32F);
        if (useSuperPoint && !mSuperPoint.detect(gray, kps, descs)) {
            useSuperPoint = false;
        }
        if (!useSuperPoint) {
            mFeatureDetector->detectAndCompute(gray, cv::noArray(), kps, descs);
        }

        if (descs.empty()) continue;

        cv::Ptr<cv::DescriptorMatcher>& activeMatcher =
            (descs.type() == CV_32F) ? mL2Matcher : mMatcher;
        std::vector<std::vector<cv::DMatch>> matches;
        activeMatcher->knnMatch(descs, mWallDescriptors, matches, 2);

        std::vector<cv::Point2f> imgPts;
        std::vector<cv::Point3f> objPts;
        for (auto& match : matches) {
            if (match.size() < 2) continue;
            if (match[0].distance < 0.75f * match[1].distance) {
                imgPts.push_back(kps[match[0].queryIdx].pt);
                objPts.push_back(mWallKeypoints3D[match[0].trainIdx]);
            }
        }

        if (imgPts.size() >= 15) {
            cv::Mat rvec, tvec;
            std::vector<int> inliers;
            // Physical intrinsics from Mapping camera
            cv::Mat intr = (cv::Mat_<double>(3,3) << 1000.0, 0, 960.0, 0, 1000.0, 540.0);
            if (cv::solvePnPRansac(objPts, imgPts, intr, cv::Mat(), rvec, tvec, false, 100, 8.0, 0.99, inliers)) {
                if (inliers.size() >= 12) {
                    cv::Mat R;
                    cv:: Rodrigues(rvec, R);

                    // Construct 4x4 matrix from PnP result (Camera-to-World in Fingerprint Space)
                    glm::mat4 pnpMat = glm::mat4(1.0f);
                    for(int i=0; i<3; ++i) {
                        for(int j=0; j<3; ++j) pnpMat[j][i] = (float)R.at<double>(i,j);
                        pnpMat[3][i] = (float)tvec.at<double>(i);
                    }

                    // [TELEOLOGICAL CORRECTION] Snap the global anchor to match the physical fingerprint
                    std::lock_guard<std::mutex> lock(mMutex);
                    // This is a simplified version of the correction logic
                    memcpy(mAnchorMatrix, glm::value_ptr(pnpMat), 16 * sizeof(float));
                    LOGI("Relocalization: Snap-Back successful with %zu inliers", inliers.size());
                }
            }
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
}
void MobileGS::runPnPMatch(const cv::Mat& frame) {}
void MobileGS::tryUpdateFingerprint(const cv::Mat& color, const cv::Mat& depth, const float* viewMat, const float* projMat) {}
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
void MobileGS::scheduleRelocCheck(const cv::Mat& f) {}

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
void MobileGS::setArtworkFingerprint(const cv::Mat& c, const uint8_t* d, int w, int h, int s, const float* i, const float* v) {}
MobileGS::FingerprintData MobileGS::generateFingerprint(const cv::Mat& i, const cv::Mat& m, const uint8_t* d, int w, int h, int s, const float* intr, const float* v) { return {}; }
void MobileGS::sortThreadFunc() {}
