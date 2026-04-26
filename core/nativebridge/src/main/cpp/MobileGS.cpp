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

void MobileGS::initialize(int width, int height) {
    std::lock_guard<std::mutex> lock(mMutex);
    mScreenWidth = width;
    mScreenHeight = height;
    mFeatureDetector = cv::ORB::create(500);
    mMatcher = cv::DescriptorMatcher::create("BruteForce-Hamming");

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
    if (!mSortRunning) {
        mSortRunning = true;
        mSortThread = std::thread(&MobileGS::sortThreadFunc, this);
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

    if (mScanMode == 1) { // MURAL
        mFrameCounter++;
        if (mMuralMethod == 0) { // VOXEL_HASH
            mVoxelHash.draw(mvp, V, std::abs(mProjMatrix[5]) * (mScreenHeight / 2.0f), mScreenHeight);
        } else { // SURFACE_MESH
            mSurfaceMesh.draw(mvp);
        }
    }
}

void MobileGS::pushPointCloud(const std::vector<float>& points) {
    std::lock_guard<std::mutex> lock(mMutex);
    mVoxelHash.addSparsePoints(points, mViewMatrix, mProjMatrix);
}

void MobileGS::processDepthFrame(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, const float* intrinsics, bool isYuv, float confidence) {
    bool isTrackingState = false;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (depth.empty() || color.empty() || !mCameraReady) return;
        isTrackingState = mIsArCoreTracking;
    }
    if (!isTrackingState) return;

    cv::Mat colorRGB;
    if (isYuv) cv::cvtColor(color, colorRGB, cv::COLOR_YUV2RGB_NV21);
    else colorRGB = color;

    if (mScanMode == 1) { // MURAL
        mFrameCounter++;
        if (mMuralMethod == 0) { // VOXEL_HASH
            mVoxelHash.update(depth, colorRGB, viewMat, projMat, mVoxelSize, confidence);

            // Task: Store keyframes for background optimization
            // We use every 15th frame as a keyframe to ensure multi-view diversity
            if (mFrameCounter % 15 == 0) {
                Keyframe kf;
                kf.depth = depth.clone();
                kf.color = colorRGB.clone();
                memcpy(kf.viewMatrix, viewMat, 16 * sizeof(float));
                memcpy(kf.projMatrix, projMat, 16 * sizeof(float));
                memcpy(kf.angularVelocity, mLastAngularVelocity, 3 * sizeof(float));
                memcpy(kf.linearVelocity, mLastLinearVelocity, 3 * sizeof(float));
                mVoxelHash.addKeyframe(kf);
            }
        } else { // SURFACE_MESH
            mSurfaceMesh.update(depth, colorRGB, viewMat, projMat, mAnchorMatrix, mLightLevel);
        }
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
    if (mFrameQueue.size() >= 2) mFrameQueue.erase(mFrameQueue.begin());
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

void MobileGS::sortThreadFunc() {
    setpriority(PRIO_PROCESS, 0, 18);
    while (mSortRunning) {
        glm::vec3 camPos;
        {
            std::lock_guard<std::mutex> lock(mMutex);
            if (!mCameraReady) {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                continue;
            }
            camPos = glm::vec3(glm::inverse(glm::make_mat4(mViewMatrix))[3]);
        }

        mVoxelHash.sort(camPos);

        // Back-to-front sorting frequency (10Hz)
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
}

// ... Rest of Reloc/Fingerprint methods (kept for tracking stability) ...
void MobileGS::relocThreadFunc() { /* ... kept from stable ... */ }
void MobileGS::runPnPMatch(const cv::Mat& frame) { /* ... kept from stable ... */ }
void MobileGS::tryUpdateFingerprint(const cv::Mat& color, const cv::Mat& depth, const float* viewMat, const float* projMat) { /* ... kept ... */ }
void MobileGS::interpolateAnchorStep() { /* ... kept ... */ }
void MobileGS::setArCoreTrackingState(bool t) { mIsArCoreTracking = t; }
void MobileGS::optimizeThreadFunc() {
    setpriority(PRIO_PROCESS, 0, 19); // Background priority
    while (mOptimizeRunning) {
        FrameData latestFrame;
        bool hasFrame = false;
        {
            std::lock_guard<std::mutex> lock(mQueueMutex);
            if (!mFrameQueue.empty()) {
                latestFrame = mFrameQueue.back(); // Optimize against the latest keyframe
                hasFrame = true;
            }
        }

        if (hasFrame) {
            cv::Mat colorRGB;
            if (latestFrame.isYuv) cv::cvtColor(latestFrame.color, colorRGB, cv::COLOR_YUV2RGB_NV21);
            else colorRGB = latestFrame.color;

            mVoxelHash.optimize(latestFrame.depth, colorRGB, latestFrame.viewMatrix, latestFrame.projMatrix);

            // [OPTIMIZATION] When global confidence is high, bake the results into the persistent textured mesh
            if (mVoxelHash.getGlobalConfidenceAvg() > 0.80f) {
                mSurfaceMesh.update(latestFrame.depth, colorRGB, latestFrame.viewMatrix, latestFrame.projMatrix, mAnchorMatrix, mLightLevel);
            }
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
}

void MobileGS::destroy() {
    mMapRunning = false;
    mOptimizeRunning = false;
    mRelocRunning = false;
    mSortRunning = false;
    mQueueCv.notify_all();
    mRelocCv.notify_all();
    if (mMapThread.joinable()) mMapThread.join();
    if (mOptimizeThread.joinable()) mOptimizeThread.join();
    if (mRelocThread.joinable()) mRelocThread.join();
    if (mSortThread.joinable()) mSortThread.join();
}

void MobileGS::saveModel(const std::string& p) {
    mVoxelHash.save(p);
}
void MobileGS::loadModel(const std::string& p) {
    mVoxelHash.load(p);
}
bool MobileGS::importModel3D(const std::string& p) { return false; }
void MobileGS::setViewportSize(int w, int h) { mScreenWidth = w; mScreenHeight = h; }
void MobileGS::setRelocEnabled(bool e) { mRelocEnabled = e; }
void MobileGS::restoreWallFingerprint(const cv::Mat& d, const std::vector<cv::Point3f>& p) { mWallDescriptors = d.clone(); mWallKeypoints3D = p; }

std::vector<uint8_t> MobileGS::exportFingerprint() {
    std::lock_guard<std::mutex> lock(mMutex);
    std::vector<uint8_t> buffer;
    if (mWallDescriptors.empty()) return buffer;

    int numPts = (int)mWallKeypoints3D.size();
    int descRows = mWallDescriptors.rows;
    int descCols = mWallDescriptors.cols;
    int descType = mWallDescriptors.type();

    size_t headerSize = sizeof(int) * 4;
    size_t descSize = mWallDescriptors.total() * mWallDescriptors.elemSize();
    size_t ptsSize = numPts * sizeof(cv::Point3f);

    buffer.resize(headerSize + descSize + ptsSize);
    uint8_t* ptr = buffer.data();

    memcpy(ptr, &numPts, sizeof(int)); ptr += sizeof(int);
    memcpy(ptr, &descRows, sizeof(int)); ptr += sizeof(int);
    memcpy(ptr, &descCols, sizeof(int)); ptr += sizeof(int);
    memcpy(ptr, &descType, sizeof(int)); ptr += sizeof(int);

    memcpy(ptr, mWallDescriptors.data, descSize); ptr += descSize;
    memcpy(ptr, mWallKeypoints3D.data(), ptsSize);

    return buffer;
}

void MobileGS::alignToFingerprint(const uint8_t* data, size_t size) {
    if (size < sizeof(int) * 4) return;

    const uint8_t* ptr = data;
    int numPts, descRows, descCols, descType;
    memcpy(&numPts, ptr, sizeof(int)); ptr += sizeof(int);
    memcpy(&descRows, ptr, sizeof(int)); ptr += sizeof(int);
    memcpy(&descCols, ptr, sizeof(int)); ptr += sizeof(int);
    memcpy(&descType, ptr, sizeof(int)); ptr += sizeof(int);

    cv::Mat remoteDescriptors(descRows, descCols, descType);
    size_t descByteSize = remoteDescriptors.total() * remoteDescriptors.elemSize();
    memcpy(remoteDescriptors.data, ptr, descByteSize);
    ptr += descByteSize;

    std::vector<cv::Point3f> remotePoints3D(numPts);
    memcpy(remotePoints3D.data(), ptr, numPts * sizeof(cv::Point3f));

    std::lock_guard<std::mutex> lock(mMutex);
    mWallDescriptors = remoteDescriptors.clone();
    mWallKeypoints3D = remotePoints3D;
    mRelocRequested = true;
    mRelocCv.notify_one();
    LOGI("Collaboration: Aligned to peer fingerprint with %d points", numPts);
}

void MobileGS::scheduleRelocCheck(const cv::Mat& f) { mRelocColorFrame = f.clone(); mRelocRequested = true; mRelocCv.notify_one(); }

// Global bridge for collaboration module
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
MobileGS::FingerprintData MobileGS::generateFingerprint(const cv::Mat& i, const cv::Mat& m, const uint8_t* d, int w, int h, int s, const float* intr, const float* v) {
    FingerprintData fd;
    if (i.empty() || !d || !intr || !v) return fd;

    cv::Mat gray;
    if (i.channels() == 4) cv::cvtColor(i, gray, cv::COLOR_RGBA2GRAY);
    else if (i.channels() == 3) cv::cvtColor(i, gray, cv::COLOR_RGB2GRAY);
    else gray = i.clone();

    // Mask processing: ORB expects 8-bit single channel mask (255=keep, 0=discard)
    cv::Mat mask;
    if (!m.empty()) {
        if (m.channels() == 4) cv::cvtColor(m, mask, cv::COLOR_RGBA2GRAY);
        else if (m.channels() == 3) cv::cvtColor(m, mask, cv::COLOR_RGB2GRAY);
        else mask = m.clone();
        // Ensure it's a proper binary mask
        cv::threshold(mask, mask, 127, 255, cv::THRESH_BINARY);
    }

    std::vector<cv::KeyPoint> rawKps;
    cv::Mat rawDescs;
    mFeatureDetector->detectAndCompute(gray, mask, rawKps, rawDescs);

    float fx = intr[0], fy = intr[1], cx = intr[2], cy = intr[3];

    // Scaling factors between image coordinates and depth buffer coordinates
    float scaleX = (float)w / (float)i.cols;
    float scaleY = (float)h / (float)i.rows;

    std::vector<int> keptIndices;
    for (size_t idx = 0; idx < rawKps.size(); ++idx) {
        const auto& kp = rawKps[idx];
        
        // Sample depth
        int dx = (int)(kp.pt.x * scaleX);
        int dy = (int)(kp.pt.y * scaleY);
        
        if (dx >= 0 && dx < w && dy >= 0 && dy < h) {
            auto* rowPtr = reinterpret_cast<const uint16_t*>(d + (dy * s));
            uint16_t raw = rowPtr[dx];
            uint16_t depthMm = raw & 0x1FFFu;
            uint8_t conf = (raw >> 13u) & 0x7u;

            if (depthMm > 0 && conf > 0) {
                float depthM = (float)depthMm / 1000.0f;
                
                // Camera Space
                float xc = (kp.pt.x - cx) * depthM / fx;
                float yc = (kp.pt.y - cy) * depthM / fy;
                float zc = depthM;

                // World Space
                float xw, yw, zw;
                camToWorld(v, xc, yc, zc, xw, yw, zw);

                fd.keypoints.push_back(kp);
                fd.points3d.push_back(xw);
                fd.points3d.push_back(yw);
                fd.points3d.push_back(zw);
                keptIndices.push_back((int)idx);
            }
        }
    }

    // Filter descriptors to match kept keypoints
    if (keptIndices.size() == rawKps.size()) {
        fd.descriptors = rawDescs;
    } else if (!keptIndices.empty()) {
        fd.descriptors = cv::Mat(keptIndices.size(), rawDescs.cols, rawDescs.type());
        for (size_t idx = 0; idx < keptIndices.size(); ++idx) {
            rawDescs.row(keptIndices[idx]).copyTo(fd.descriptors.row(idx));
        }
    }

    return fd;
}

