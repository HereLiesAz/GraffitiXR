#include "StereoProcessor.h"
#include <opencv2/imgproc.hpp>
#include <android/log.h>

#define TAG "StereoProcessor"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

StereoProcessor::StereoProcessor() {
    // Initialize StereoSGBM with reasonable defaults for mobile
    // These parameters might need tuning based on the specific device camera baseline and resolution
    int minDisparity = 0;
    int numDisparities = 64; // Must be divisible by 16
    int blockSize = 5; // Odd number, >= 1
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

    // Default Q matrix (reprojection matrix)
    // This is a placeholder. In a real scenario, this comes from stereoRectify()
    Q = cv::Mat::eye(4, 4, CV_32F);
    Q.at<float>(3, 2) = -1.0f / 100.0f; // Inverse baseline guess
}

StereoProcessor::~StereoProcessor() {
    // cv::Ptr handles cleanup
}

void StereoProcessor::process(
    const unsigned char* leftData, int leftWidth, int leftHeight, int leftStride,
    const unsigned char* rightData, int rightWidth, int rightHeight, int rightStride
) {
    if (!leftData || !rightData) {
        LOGE("Invalid image data pointers");
        return;
    }

    if (leftWidth != rightWidth || leftHeight != rightHeight) {
        LOGE("Left and Right image dimensions mismatch: %dx%d vs %dx%d", leftWidth, leftHeight, rightWidth, rightHeight);
        return;
    }

    // Wrap raw buffers in cv::Mat
    // Assuming GRAYSCALE (Y-plane) input
    cv::Mat leftImg(leftHeight, leftWidth, CV_8UC1, (void*)leftData, leftStride);
    cv::Mat rightImg(rightHeight, rightWidth, CV_8UC1, (void*)rightData, rightStride);

    // Downscale for performance if needed (SGBM is expensive)
    cv::Mat leftSmall, rightSmall;
    float scale = 0.5f;
    cv::resize(leftImg, leftSmall, cv::Size(), scale, scale);
    cv::resize(rightImg, rightSmall, cv::Size(), scale, scale);

    // Compute Disparity
    cv::Mat disparity16S;
    stereoSgbm->compute(leftSmall, rightSmall, disparity16S);

    // Reproject to 3D
    cv::Mat points3D;
    cv::reprojectImageTo3D(disparity16S, points3D, Q, true);

    // TODO: Pass points3D to SLAM system or visualize
    // For verification, just log some stats
    double minVal, maxVal;
    cv::minMaxLoc(disparity16S, &minVal, &maxVal);
    LOGD("Disparity Computed. Min: %f, Max: %f", minVal, maxVal);
}
