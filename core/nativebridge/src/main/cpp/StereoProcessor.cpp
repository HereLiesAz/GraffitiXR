#include "StereoProcessor.h"
#include "MobileGS.h"
#include <opencv2/imgproc.hpp>
#include <android/log.h>

#define TAG "StereoProcessor"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

StereoProcessor::StereoProcessor() {
    int minDisparity = 0;
    int numDisparities = 64;
    int blockSize = 5;
    int P1 = 8 * 1 * blockSize * blockSize;
    int P2 = 32 * 1 * blockSize * blockSize;
    int disp12MaxDiff = 1;
    int preFilterCap = 63;
    int uniquenessRatio = 10;
    int speckleWindowSize = 100;
    int speckleRange = 32;

    stereoSgbm = cv::StereoSGBM::create(
            minDisparity, numDisparities, blockSize,
            P1, P2, disp12MaxDiff, preFilterCap, uniquenessRatio,
            speckleWindowSize, speckleRange, cv::StereoSGBM::MODE_SGBM
    );

    Q = cv::Mat::eye(4, 4, CV_32F);
    Q.at<float>(3, 2) = -1.0f / 100.0f; // Inverse baseline guess
}

StereoProcessor::~StereoProcessor() {}

void StereoProcessor::process(
        MobileGS* engine,
        const unsigned char* leftData, int leftWidth, int leftHeight, int leftStride,
        const unsigned char* rightData, int rightWidth, int rightHeight, int rightStride
) {
    if (!leftData || !rightData || !engine) return;
    if (leftWidth != rightWidth || leftHeight != rightHeight) return;

    cv::Mat leftImg(leftHeight, leftWidth, CV_8UC1, (void*)leftData, leftStride);
    cv::Mat rightImg(rightHeight, rightWidth, CV_8UC1, (void*)rightData, rightStride);

    cv::Mat leftSmall, rightSmall;
    float scale = 0.5f;
    cv::resize(leftImg, leftSmall, cv::Size(), scale, scale);
    cv::resize(rightImg, rightSmall, cv::Size(), scale, scale);

    cv::Mat disparity16S;
    stereoSgbm->compute(leftSmall, rightSmall, disparity16S);

    cv::Mat points3D;
    cv::reprojectImageTo3D(disparity16S, points3D, Q, true);

    // FIX: Extract valid points and push to SLAM engine
    std::vector<cv::Point3f> validPoints;
    validPoints.reserve(points3D.rows * points3D.cols / 4); // Pre-allocate

    for (int y = 0; y < points3D.rows; y++) {
        for (int x = 0; x < points3D.cols; x++) {
            cv::Point3f pt = points3D.at<cv::Point3f>(y, x);
            // Ignore infinity/invalid depth
            if (pt.z > 0.1f && pt.z < 5.0f) {
                validPoints.push_back(pt);
            }
        }
    }

    if (!validPoints.empty()) {
        engine->addStereoPoints(validPoints);
    }
}