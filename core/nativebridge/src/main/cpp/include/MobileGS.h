#pragma once
#include <opencv2/opencv.hpp>
#include <mutex>
#include <vector>
#include <GLES3/gl3.h>

struct Splat {
    float x, y, z;
    float r, g, b, a;
    float confidence;
};
static_assert(sizeof(Splat) == 32, "Splat struct layout changed — update .bin serialization.");

class MobileGS {
public:
    void initialize(int width, int height);
    void initGl();
    void updateCamera(float* viewMat, float* projMat);
    void updateAnchorTransform(float* transformMat);
    void processDepthFrame(const cv::Mat& depth, const cv::Mat& color);

    void setArCoreTrackingState(bool isTracking);
    bool isTracking() const;
    void attemptRelocalization(const cv::Mat& colorFrame);

    void draw();
    void destroy();
    std::mutex& getMutex() { return mMutex; }

private:
    bool performPnP(const cv::Mat& grayFrame);
    void pruneMap();
    void initShaders();

    std::mutex mMutex;
    bool mIsArCoreTracking = false;

    cv::Ptr<cv::ORB> mFeatureDetector;
    cv::Ptr<cv::DescriptorMatcher> mMatcher;

    cv::Mat mTargetDescriptors;
    std::vector<cv::KeyPoint> mTargetKeypoints;

    std::vector<Splat> splatData;

    // GLES handles
    GLuint mProgram = 0;
    GLuint mPointVbo = 0;
    GLuint mMeshVbo = 0;

    int mPointCount = 0;
    int mMeshVertexCount = 0;

    float mViewMatrix[16];
    float mProjMatrix[16];
    bool mCameraReady = false;
};