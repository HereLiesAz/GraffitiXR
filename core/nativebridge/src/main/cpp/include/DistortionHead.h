#pragma once
#include <opencv2/opencv.hpp>
#include <opencv2/dnn.hpp>
#include <opencv2/core/ocl.hpp>
#include <array>
#include <atomic>
#include <mutex>
#include <vector>

/**
 * Distortion head (design: docs/DISTORTION_HEAD.md). A small learned head on a frozen SuperPoint
 * backbone, exported self-contained to ONNX: two gray views in (current crop + canonical fingerprint
 * patch), one tensor out:
 *
 *   distortion[13] = [corners(8), pose(3 = tilt°, log2(scale), roll°), matchability(1), coverage(1)]
 *
 * corners = the planar homography between the views (the viewpoint distortion); matchability gates
 * relocalization; coverage drives painting-progress. OpenCV-DNN compatible (opset-12, plain ops).
 * Optional: inert unless the distortion_head.onnx asset is present.
 */
class DistortionHead {
public:
    DistortionHead() = default;

    bool load(const std::vector<uchar>& onnxBytes);
    bool isLoaded() const { return mLoaded; }

    /** Two gray images → distortion[13]. Images are resized to kPatch and scaled to [0,1] inside. */
    bool run(const cv::Mat& grayCur, const cv::Mat& grayFp, std::array<float, 13>& out);

    static constexpr int kPatch = 256;

private:
    cv::dnn::Net      mNet;
    std::mutex        mMutex;
    std::atomic<bool> mLoaded{false};
};
