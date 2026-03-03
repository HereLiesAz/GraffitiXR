#pragma once
#include <opencv2/opencv.hpp>
#include <opencv2/dnn.hpp>
#include <atomic>
#include <mutex>
#include <vector>

/**
 * SuperPoint neural feature detector using OpenCV DNN (ONNX backend).
 *
 * Expects an ONNX model that outputs:
 *   semi [1, 65, H/8, W/8] — keypoint probability heatmap (raw logits)
 *   desc [1, 256, H/8, W/8] — dense descriptor map
 *
 * Auto-identifies outputs by channel count (65 → semi, 256 → desc).
 * Falls back gracefully when the model is not loaded (isLoaded() == false).
 * Thread-safe: detect() serializes concurrent callers via an internal mutex.
 */
class SuperPointDetector {
public:
    SuperPointDetector() = default;

    /**
     * Load an ONNX model from raw bytes (e.g., from AssetManager).
     * Returns false if the model cannot be parsed. Thread-safe.
     */
    bool load(const std::vector<uchar>& onnxBytes);

    /** Returns true once load() has succeeded. Atomic; no lock required. */
    bool isLoaded() const { return mLoaded; }

    /**
     * Detect SuperPoint keypoints and compute descriptors.
     *
     * Output descriptors are CV_32F, N×256, L2-normalized.
     * Returns false if the model is not loaded or inference fails;
     * caller should fall back to ORB in that case.
     *
     * @param gray       8-bit grayscale input (any size)
     * @param kps        output keypoints, sorted by decreasing response
     * @param descs      output descriptors [N × 256], CV_32F
     * @param scoreThresh minimum detector score (default: 0.005)
     * @param maxKps     maximum keypoints to return (default: 500)
     */
    bool detect(const cv::Mat& gray,
                std::vector<cv::KeyPoint>& kps,
                cv::Mat& descs,
                float scoreThresh = 0.005f,
                int   maxKps      = 500);

private:
    cv::dnn::Net       mNet;
    std::mutex         mMutex;
    std::atomic<bool>  mLoaded{false};

    /**
     * NMS on the raw semi heatmap via pixel-shuffle + 9×9 local-maximum filter.
     * Fills kps sorted by descending response, capped at maxKps.
     */
    void extractKeypoints(const cv::Mat& semiTensor,
                          std::vector<cv::KeyPoint>& kps,
                          float thresh, int maxKps);

    /**
     * Bilinear descriptor sampling at keypoint positions, with per-descriptor L2 normalization.
     * desc output: [N × 256] CV_32F.
     */
    void sampleDescriptors(const cv::Mat& descTensor,
                           const std::vector<cv::KeyPoint>& kps,
                           cv::Mat& descs);
};
