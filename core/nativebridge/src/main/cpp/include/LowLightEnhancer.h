#pragma once
#include <opencv2/opencv.hpp>
#include <opencv2/dnn.hpp>
#include <atomic>
#include <mutex>
#include <vector>

class LowLightEnhancer {
public:
    LowLightEnhancer() = default;

    bool load(const std::vector<uchar>& onnxBytes);
    bool isLoaded() const { return mLoaded; }

    // input: CV_8UC3 RGB or CV_8UC4 RGBA (converted internally). output: always CV_8UC3 RGB,
    // resized back to input's original size. Returns false if model not loaded or inference
    // fails.
    bool enhance(const cv::Mat& input, cv::Mat& output);

private:
    cv::dnn::Net      mNet;
    std::mutex        mMutex;
    std::atomic<bool> mLoaded{false};
};
