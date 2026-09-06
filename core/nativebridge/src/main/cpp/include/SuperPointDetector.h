#pragma once
#include <opencv2/opencv.hpp>
#include <opencv2/dnn.hpp>
#include <opencv2/core/ocl.hpp>
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
                // Softmax over 65 bins means a completely uninformative heatmap is uniform at
                // 1/65 ~= 0.01538 -- a threshold below that filters nothing (every pixel of pure
                // noise clears it), leaving maxKps' top-K truncation as the only real bound. Set
                // at the MagicLeap reference implementation's value, right at that floor, so the
                // threshold actually means something.
                float scoreThresh = 0.015f,
                int   maxKps      = 500);

    /** Masked detection */
    bool detect(const cv::Mat& gray,
                std::vector<cv::KeyPoint>& kps,
                cv::Mat& descs,
                const cv::Mat& mask,
                // Softmax over 65 bins means a completely uninformative heatmap is uniform at
                // 1/65 ~= 0.01538 -- a threshold below that filters nothing (every pixel of pure
                // noise clears it), leaving maxKps' top-K truncation as the only real bound. Set
                // at the MagicLeap reference implementation's value, right at that floor, so the
                // threshold actually means something.
                float scoreThresh = 0.015f,
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
