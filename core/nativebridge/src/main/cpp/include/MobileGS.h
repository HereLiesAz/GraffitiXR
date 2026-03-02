#pragma once
#include <opencv2/opencv.hpp>
#include <mutex>
#include <vector>

static constexpr size_t MAX_SPLATS = 500000;

struct Splat {
    float x, y, z;
    float r, g, b, a;
    float confidence;
};

class MobileGS {
public:
    void initialize(int width, int height);
    void updateCamera(float* viewMat, float* projMat);
    void updateAnchorTransform(float* transformMat);
    void processDepthFrame(const cv::Mat& depth, const cv::Mat& color);

    void setArCoreTrackingState(bool isTracking);
    bool isTracking() const;
    void attemptRelocalization(const cv::Mat& colorFrame);

    void draw();
    std::mutex& getMutex() { return mMutex; }

private:
    bool performPnP(const cv::Mat& grayFrame);
    void pruneMap();

    std::mutex mMutex;
    bool mIsArCoreTracking = false;

    cv::Ptr<cv::ORB> mFeatureDetector;
    cv::Ptr<cv::DescriptorMatcher> mMatcher;

    cv::Mat mTargetDescriptors;
    std::vector<cv::KeyPoint> mTargetKeypoints;

    std::vector<Splat> splatData;
};