#pragma once
#include <opencv2/opencv.hpp>
#include <opencv2/dnn.hpp>
#include <atomic>
#include <mutex>
#include <vector>

/**
 * SuperPoint neural feature detector using OpenCV DNN (ONNX backend).
 */
class SuperPointDetector {
public:
    SuperPointDetector() = default;

    bool load(const std::vector<uchar>& onnxBytes);
    bool isLoaded() const { return mLoaded; }

    /** Original detection (no mask) */
    bool detect(const cv::Mat& gray,
                std::vector<cv::KeyPoint>& kps,
                cv::Mat& descs,
                float scoreThresh = 0.005f,
                int   maxKps      = 500);

    /** Masked detection */
    bool detect(const cv::Mat& gray,
                std::vector<cv::KeyPoint>& kps,
                cv::Mat& descs,
                const cv::Mat& mask,
                float scoreThresh = 0.005f,
                int   maxKps      = 500);

private:
    cv::dnn::Net       mNet;
    std::mutex         mMutex;
    std::atomic<bool>  mLoaded{false};

    void extractKeypoints(const cv::Mat& semiTensor,
                          std::vector<cv::KeyPoint>& kps,
                          float thresh, int maxKps);

    void sampleDescriptors(const cv::Mat& descTensor,
                           const std::vector<cv::KeyPoint>& kps,
                           cv::Mat& descs);
};
