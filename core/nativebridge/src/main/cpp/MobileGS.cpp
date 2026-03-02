#include "MobileGS.h"
#include <algorithm>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GraffitiJNI", __VA_ARGS__)

void MobileGS::initialize(int width, int height) {
    mFeatureDetector = cv::ORB::create(500);
    mMatcher = cv::DescriptorMatcher::create("BruteForce-Hamming");
}

void MobileGS::setArCoreTrackingState(bool isTracking) {
    std::lock_guard<std::mutex> lock(mMutex);
    mIsArCoreTracking = isTracking;
}

bool MobileGS::isTracking() const {
    return mIsArCoreTracking;
}

void MobileGS::pruneMap() {
    if (splatData.size() < MAX_SPLATS) return;

    const size_t evictCount = MAX_SPLATS / 10;  // evict bottom 10% by confidence

    std::partial_sort(splatData.begin(),
                      splatData.begin() + evictCount,
                      splatData.end(),
                      [](const Splat& a, const Splat& b) {
                          return a.confidence < b.confidence;
                      });

    splatData.erase(splatData.begin(), splatData.begin() + evictCount);
}

void MobileGS::processDepthFrame(const cv::Mat& depth, const cv::Mat& color) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mIsArCoreTracking) return;

    // ARCore is tracking. We only use this cycle to update the Gaussian
    // Splat voxels with hardware depth. PnP is completely bypassed.
    // updateVoxels(depth, color);
    //
    // Example insertion path (wired for pruning):
    // Splat s = buildSplat(depth, color);
    // splatData.push_back(s);
    // if (splatData.size() >= MAX_SPLATS) {
    //     pruneMap();
    // }
}

void MobileGS::attemptRelocalization(const cv::Mat& colorFrame) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mIsArCoreTracking || colorFrame.empty()) return;

    cv::Mat gray;
    cv::cvtColor(colorFrame, gray, cv::COLOR_RGB2GRAY);

    if (performPnP(gray)) {
        LOGI("Teleological target re-acquired via OpenCV. Awaiting ARCore handoff.");
        // At this point, you'd trigger a callback to Kotlin to instantiate
        // a new ARCore Anchor using the solved pose matrix.
    }
}

bool MobileGS::performPnP(const cv::Mat& grayFrame) {
    if (mTargetDescriptors.empty()) return false;

    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    mFeatureDetector->detectAndCompute(grayFrame, cv::noArray(), keypoints, descriptors);

    if (descriptors.empty()) return false;

    std::vector<cv::DMatch> matches;
    mMatcher->match(descriptors, mTargetDescriptors, matches);

    // Filter matches and run solvePnPRansac here.
    // If inliers > threshold, we found the wall.
    return false; // Stubbed until target initialization is wired up
}

void MobileGS::updateCamera(float* viewMat, float* projMat) {
    // Update internal MVP matrices
}

void MobileGS::updateAnchorTransform(float* transformMat) {
    // Update local coordinate space
}

void MobileGS::draw() {
    // Execute OpenGL ES draw calls for the splats
}