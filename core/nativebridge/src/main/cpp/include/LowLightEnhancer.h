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

    // input/output: CV_8UC3 RGB. Returns false if model not loaded or inference fails.
    bool enhance(const cv::Mat& input, cv::Mat& output);

private:
    cv::dnn::Net      mNet;
    std::mutex        mMutex;
    std::atomic<bool> mLoaded{false};
};
