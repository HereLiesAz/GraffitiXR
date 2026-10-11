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

    /**
     * Default keypoint-score threshold. The score is one pixel's share of its 8x8 cell's 65-way
     * softmax, where bin 64 is the "no keypoint" dustbin.
     *
     * An earlier comment here argued that a featureless heatmap is uniform at 1/65 ~= 0.0154, so any
     * threshold below that "filters nothing" -- and then set 0.015, below that floor, contradicting
     * itself. The premise is wrong for this network: on a featureless cell the DUSTBIN takes the
     * mass. Measured on the bundled superpoint.onnx (rpautrat/SuperPoint v6 weights, see
     * assets/superpoint.LICENSE.txt): flat and blurred-noise inputs put ~0.9995 on the dustbin and
     * yield zero local maxima at every threshold from 0.005 to 0.1. So the uninformative floor for a
     * pixel bin is ~0, not 1/65, and the threshold is a sensitivity knob rather than a noise floor.
     *
     * Why 0.015 for that knob. Measured on a synthetic sparse "spray marks" frame (this app's case):
     * 0.005 -> 369 maxima, 0.01 -> 320, 0.015 -> 154, 0.02 -> 123, 0.05 -> 115. Below ~0.015 the
     * count doubles with responses an order of magnitude weaker than the corners above it -- the
     * population that repeats worst across viewpoint, i.e. that matches a stored fingerprint least
     * (rpautrat's own default, 0.005, is tuned for a top-K evaluation that discards them anyway).
     * Above it the yield on sparse walls drops ~20% by 0.02 for no gain in a dense scene, where
     * maxKps' top-K already bounds the set. 0.015 sits at that knee, and it is also the value every
     * fingerprint saved so far was detected with, so keeping it keeps live detection consistent with
     * the stored reference sets. (It happens to equal MagicLeap's demo default; that is not the
     * reason, these are not MagicLeap's weights.)
     */
    static constexpr float kDefaultScoreThresh = 0.015f;
    /**
     * Longest side, in pixels, of the image actually fed to the network. Larger inputs are
     * downscaled UNIFORMLY (aspect preserved) so the longest side equals this; the model has
     * dynamic H/W axes (superpoint.onnx: image [1,1,H,W] -> semi/desc [1,C,H/8,W/8]), so no fixed
     * network shape forces a distortion. 640 matches the previous 640x480 compute budget for a 4:3
     * landscape frame.
     */
    static constexpr int kMaxInputSide = 640;

    /** How an input of a given size is mapped onto the network input (see detect()). */
    struct InputGeometry {
        int scaledW = 0, scaledH = 0;   //!< image content size after the uniform resize
        int netW = 0, netH = 0;         //!< network input size: scaled size padded up to a multiple of 8
        float scaleX = 1.0f, scaleY = 1.0f; //!< network-input pixel -> source pixel
        bool resized = false;           //!< false when the content is the source pixels verbatim
    };
    /**
     * Pure geometry, exposed for host tests. The content is resized uniformly so the longest side
     * is at most [maxSide] (never upscaled), then zero-padded on the right/bottom to the next
     * multiple of 8 (the network's stride). Padding instead of resizing to a multiple of 8 keeps
     * the aspect ratio exact; keypoints found in the padding are discarded by detect().
     */
    static InputGeometry inputGeometry(int w, int h, int maxSide = kMaxInputSide);

    bool load(const std::vector<uchar>& onnxBytes);
    bool isLoaded() const { return mLoaded; }

    /** Original detection (no mask) */
    bool detect(const cv::Mat& gray,
                std::vector<cv::KeyPoint>& kps,
                cv::Mat& descs,
                float scoreThresh = kDefaultScoreThresh,
                int   maxKps      = 500);

    /** Masked detection */
    bool detect(const cv::Mat& gray,
                std::vector<cv::KeyPoint>& kps,
                cv::Mat& descs,
                const cv::Mat& mask,
                float scoreThresh = kDefaultScoreThresh,
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
