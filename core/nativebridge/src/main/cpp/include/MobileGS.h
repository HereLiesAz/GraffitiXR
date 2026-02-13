#ifndef MOBILE_GS_H
#define MOBILE_GS_H

#include <vector>
#include <mutex>
#include <memory>
#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>

class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    void Initialize(int width, int height);
    void Update(const cv::Mat& cameraFrame, const float* viewMatrix, const float* projectionMatrix);
    void Draw();
    void Cleanup();

    // Teleological SLAM methods
    void SetTargetDescriptors(const cv::Mat& descriptors);
    bool IsTrackingTarget() const;

private:
    int mWidth;
    int mHeight;
    bool mIsInitialized;

    // SLAM Components
    cv::Ptr<cv::ORB> mOrb;
    cv::Ptr<cv::DescriptorMatcher> mMatcher;

    // The Map (Current Reality)
    std::vector<cv::KeyPoint> mMapKeypoints; // 2D (Legacy/Debug)
    std::vector<cv::Point3f> mMapPoints3D;   // 3D World Points
    cv::Mat mMapDescriptors;
    std::mutex mMapMutex;

    // The Target (The Goal/Overlay)
    cv::Mat mTargetDescriptors;
    bool mHasTarget;

    // Matrices
    float mViewMatrix[16];
    float mProjMatrix[16];

    // Internal methods
    void ProcessFrame(const cv::Mat& frame);
    void MatchAndFuse(const std::vector<cv::KeyPoint>& keypoints, const cv::Mat& descriptors);

    // Math Helpers
    cv::Point2f ProjectPoint(const cv::Point3f& p3d);
    cv::Point3f UnprojectPoint(const cv::KeyPoint& kpt, float depth);
    void MultiplyMatrixVector(const float* matrix, const float* in, float* out);
};

#endif // MOBILE_GS_H