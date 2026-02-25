#ifndef STEREO_PROCESSOR_H
#define STEREO_PROCESSOR_H

#include <opencv2/core.hpp>
#include <opencv2/calib3d.hpp>
#include <vector>

// Forward declaration
class MobileGS;

class StereoProcessor {
public:
    StereoProcessor();
    ~StereoProcessor();

    // FIX: Added engine reference to allow data pushback
    void process(
            MobileGS* engine,
            const unsigned char* leftData, int leftWidth, int leftHeight, int leftStride,
            const unsigned char* rightData, int rightWidth, int rightHeight, int rightStride
    );

private:
    cv::Ptr<cv::StereoSGBM> stereoSgbm;
    cv::Mat Q;
};

#endif // STEREO_PROCESSOR_H