#ifndef GRAFFITIXR_STEREO_PROCESSOR_H
#define GRAFFITIXR_STEREO_PROCESSOR_H

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/calib3d.hpp>
#include <vector>
#include <cstdint>

/**
 * Handles disparity mapping and depth estimation from dual camera streams.
 */
class StereoProcessor {
public:
    StereoProcessor();
    ~StereoProcessor();

    /**
     * Processes left and right frame buffers to generate a depth map.
     */
    void processStereo(int8_t* leftData, int8_t* rightData, int width, int height);

    /**
     * Returns the last computed disparity map.
     */
    cv::Mat getDisparityMap() const;

private:
    cv::Mat mLeftFrame;
    cv::Mat mRightFrame;
    cv::Mat mDisparityMap;

    // StereoBM or StereoSGBM instance
    cv::Ptr<cv::StereoSGBM> mStereoMatcher;
};

#endif // GRAFFITIXR_STEREO_PROCESSOR_H