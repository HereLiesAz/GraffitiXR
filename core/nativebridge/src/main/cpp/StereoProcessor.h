#ifndef STEREO_PROCESSOR_H
#define STEREO_PROCESSOR_H

#include <opencv2/core.hpp>
#include <opencv2/calib3d.hpp>
#include <vector>

class StereoProcessor {
public:
    StereoProcessor();
    ~StereoProcessor();

    void process(
        const unsigned char* leftData, int leftWidth, int leftHeight, int leftStride,
        const unsigned char* rightData, int rightWidth, int rightHeight, int rightStride
    );

private:
    cv::Ptr<cv::StereoSGBM> stereoSgbm;
    // Intrinsics (placeholder for now, would typically be passed or calibrated)
    cv::Mat Q;
};

#endif // STEREO_PROCESSOR_H
