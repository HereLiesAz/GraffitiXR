#include "include/LowLightEnhancer.h"
#include <android/log.h>
#include <algorithm>
#include <opencv2/core/ocl.hpp>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "LowLightEnhancer", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LowLightEnhancer", __VA_ARGS__)

bool LowLightEnhancer::load(const std::vector<uchar>& onnxBytes) {
    std::lock_guard<std::mutex> lock(mMutex);
    mLoaded = false;
    try {
        mNet = cv::dnn::readNetFromONNX(onnxBytes);
        if (mNet.empty()) return false;
        mNet.setPreferableBackend(cv::dnn::DNN_BACKEND_DEFAULT);
        if (cv::ocl::haveOpenCL()) {
            mNet.setPreferableTarget(cv::dnn::DNN_TARGET_OPENCL);
            LOGD("Using OpenCL backend");
        } else {
            mNet.setPreferableTarget(cv::dnn::DNN_TARGET_CPU);
            LOGD("OpenCL unavailable, using CPU backend");
        }
        mLoaded = true;
        return true;
    } catch (...) {
        LOGE("Failed to load ONNX model");
        return false;
    }
}

bool LowLightEnhancer::enhance(const cv::Mat& input, cv::Mat& output) {
    if (!mLoaded) return false;
    std::lock_guard<std::mutex> lock(mMutex);
    if (mNet.empty()) return false;
    try {
        static const int INF_W = 600;
        static const int INF_H = 400;

        cv::Mat resized;
        cv::resize(input, resized, cv::Size(INF_W, INF_H), 0, 0, cv::INTER_AREA);

        cv::Mat f;
        resized.convertTo(f, CV_32F, 1.0 / 255.0);
        cv::Mat blob = cv::dnn::blobFromImage(f);  // shape [1,3,H,W]

        mNet.setInput(blob);
        cv::Mat out = mNet.forward();  // shape [1,3,H,W], float [0,1]

        if (out.dims < 4 || out.size[1] != 3) return false;
        const int H = out.size[2];
        const int W = out.size[3];
        const float* data = (const float*)out.data;
        const int hw = H * W;

        cv::Mat result(H, W, CV_8UC3);
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                const int idx = y * W + x;
                result.at<cv::Vec3b>(y, x) = {
                    (uchar)std::min(255, (int)(data[idx]          * 255.0f)),
                    (uchar)std::min(255, (int)(data[hw + idx]     * 255.0f)),
                    (uchar)std::min(255, (int)(data[2 * hw + idx] * 255.0f))
                };
            }
        }
        cv::resize(result, output, input.size(), 0, 0, cv::INTER_LINEAR);
        return true;
    } catch (...) {
        LOGE("Inference failed");
        return false;
    }
}
