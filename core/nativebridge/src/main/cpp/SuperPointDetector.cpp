// ~~~ FILE: ./core/nativebridge/src/main/cpp/SuperPointDetector.cpp ~~~
#include "include/SuperPointDetector.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <numeric>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "SuperPoint", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SuperPoint", __VA_ARGS__)

// ─── load() ──────────────────────────────────────────────────────────────────

bool SuperPointDetector::load(const std::vector<uchar>& onnxBytes) {
    std::lock_guard<std::mutex> lock(mMutex);
    mLoaded = false;
    try {
        mNet = cv::dnn::readNetFromONNX(onnxBytes);
        mNet.setPreferableBackend(cv::dnn::DNN_BACKEND_DEFAULT);
        mNet.setPreferableTarget(cv::dnn::DNN_TARGET_CPU);
        bool ok = !mNet.empty();
        mLoaded = ok;
        LOGD("Model load: %s (%zu bytes)", ok ? "SUCCESS" : "FAILED", onnxBytes.size());
        return ok;
    } catch (const cv::Exception& e) {
        LOGE("OpenCV exception loading model: %s", e.what());
        return false;
    } catch (...) {
        LOGE("Unknown exception loading model");
        return false;
    }
}

// ─── detect() ────────────────────────────────────────────────────────────────

bool SuperPointDetector::detect(const cv::Mat& gray,
                                std::vector<cv::KeyPoint>& kps,
                                cv::Mat& descs,
                                float scoreThresh,
                                int   maxKps) {
    if (!mLoaded) return false;
    std::lock_guard<std::mutex> lock(mMutex);
    if (mNet.empty()) return false;

    // Build a float blob [1, 1, H, W] normalized to [0, 1]
    // NEW: Downsample high-res input to 640x480 for real-time performance on mobile
    cv::Mat input;
    float scaleX = 1.0f, scaleY = 1.0f;
    if (gray.cols > 640 || gray.rows > 480) {
        // Ensure dimensions are multiples of 8 for SuperPoint architecture
        int targetW = 640;
        int targetH = 480;
        cv::resize(gray, input, cv::Size(targetW, targetH), 0, 0, cv::INTER_AREA);
        scaleX = (float)gray.cols / (float)targetW;
        scaleY = (float)gray.rows / (float)targetH;
    } else {
        // Even for small images, ensure multiples of 8
        int targetW = (gray.cols / 8) * 8;
        int targetH = (gray.rows / 8) * 8;
        if (targetW != gray.cols || targetH != gray.rows) {
            cv::resize(gray, input, cv::Size(targetW, targetH), 0, 0, cv::INTER_AREA);
            scaleX = (float)gray.cols / (float)targetW;
            scaleY = (float)gray.rows / (float)targetH;
        } else {
            input = gray;
        }
    }

    cv::Mat f;
    input.convertTo(f, CV_32F, 1.0 / 255.0);
    cv::Mat blob = cv::dnn::blobFromImage(f);  // NCHW

    try {
        mNet.setInput(blob);

        // Retrieve all terminal outputs by name; auto-detect semi vs desc by shape
        std::vector<cv::String> outNames = mNet.getUnconnectedOutLayersNames();
        std::vector<cv::Mat> outputs;
        mNet.forward(outputs, outNames);

        const cv::Mat* semiPtr = nullptr;
        const cv::Mat* descPtr = nullptr;
        for (const cv::Mat& o : outputs) {
            if (o.dims < 4) continue;
            // OpenCV NCHW: size[1] is channels
            if (o.size[1] == 65)  semiPtr = &o;
            if (o.size[1] == 256) descPtr = &o;
        }
        if (!semiPtr || !descPtr) {
            LOGE("Could not identify semi/desc outputs (got %zu tensors)", outputs.size());
            return false;
        }

        kps.clear();
        extractKeypoints(*semiPtr, kps, scoreThresh, maxKps);

        // Upscale keypoints back to original resolution
        if (scaleX != 1.0f || scaleY != 1.0f) {
            for (auto& kp : kps) {
                kp.pt.x *= scaleX;
                kp.pt.y *= scaleY;
            }
        }

        if (kps.empty()) return false;

        descs = cv::Mat();
        sampleDescriptors(*descPtr, kps, descs);
        return true;

    } catch (const cv::Exception& e) {
        LOGE("Forward pass failed: %s", e.what());
        return false;
    }
}

// ─── extractKeypoints() ──────────────────────────────────────────────────────

void SuperPointDetector::extractKeypoints(const cv::Mat& semiTensor,
                                          std::vector<cv::KeyPoint>& kps,
                                          float thresh, int maxKps) {
    // semiTensor: [1, 65, Hc, Wc]  (Hc = H/8, Wc = W/8)
    if (semiTensor.dims < 4) return;
    int Hc = semiTensor.size[2];
    int Wc = semiTensor.size[3];
    const float* sp = (const float*)semiTensor.data;

    // Pixel-shuffle + softmax → dense H×W score map
    cv::Mat scores(Hc * 8, Wc * 8, CV_32F, cv::Scalar(0.0f));

    for (int r = 0; r < Hc; ++r) {
        for (int c = 0; c < Wc; ++c) {
            // Gather 65 logits for this 8×8 cell
            float vals[65];
            for (int k = 0; k < 65; ++k)
                vals[k] = sp[k * Hc * Wc + r * Wc + c];

            // Softmax over all 65 channels (numerically stable)
            float mx  = *std::max_element(vals, vals + 65);
            float sum = 0.0f;
            for (int k = 0; k < 65; ++k) { vals[k] = std::exp(vals[k] - mx); sum += vals[k]; }
            for (int k = 0; k < 65; ++k) vals[k] /= sum;

            // Channel k (0..63) maps to pixel (dy = k/8, dx = k%8) inside the 8×8 block
            // Channel 64 is the dustbin — skip it
            for (int dy = 0; dy < 8; ++dy)
                for (int dx = 0; dx < 8; ++dx)
                    scores.at<float>(r * 8 + dy, c * 8 + dx) = vals[dy * 8 + dx];
        }
    }

    // Local-maximum NMS with 9×9 window (radius = 4).
    // Skip a 4-pixel border to avoid edge artifacts.
    std::vector<cv::KeyPoint> tmp;
    for (int r = 4; r < scores.rows - 4; ++r) {
        for (int c = 4; c < scores.cols - 4; ++c) {
            float v = scores.at<float>(r, c);
            if (v < thresh) continue;
            bool isMax = true;
            for (int dr = -4; dr <= 4 && isMax; ++dr)
                for (int dc = -4; dc <= 4 && isMax; ++dc)
                    if (!(dr == 0 && dc == 0) && scores.at<float>(r + dr, c + dc) >= v)
                        isMax = false;
            if (isMax)
                tmp.push_back(cv::KeyPoint((float)c, (float)r, 1.0f, -1.0f, v));
        }
    }

    // Sort descending by score, keep at most maxKps
    std::sort(tmp.begin(), tmp.end(),
              [](const cv::KeyPoint& a, const cv::KeyPoint& b) {
                  return a.response > b.response;
              });
    if ((int)tmp.size() > maxKps) tmp.resize(maxKps);
    kps = std::move(tmp);
}

// ─── sampleDescriptors() ─────────────────────────────────────────────────────

void SuperPointDetector::sampleDescriptors(const cv::Mat& descTensor,
                                           const std::vector<cv::KeyPoint>& kps,
                                           cv::Mat& descs) {
    // descTensor: [1, 256, Hd, Wd]  (Hd = H/8, Wd = W/8)
    if (descTensor.dims < 4 || kps.empty()) return;
    int D  = descTensor.size[1];   // 256
    int Hd = descTensor.size[2];
    int Wd = descTensor.size[3];
    const float* dp = (const float*)descTensor.data;

    descs.create((int)kps.size(), D, CV_32F);

    for (int i = 0; i < (int)kps.size(); ++i) {
        // Map keypoint pixel coords → descriptor-space coords (scale by 1/8)
        float u  = kps[i].pt.x / 8.0f;
        float v  = kps[i].pt.y / 8.0f;
        int   u0 = std::max(0,      (int)u);
        int   v0 = std::max(0,      (int)v);
        int   u1 = std::min(Wd - 1, u0 + 1);
        int   v1 = std::min(Hd - 1, v0 + 1);
        float fu = u - u0, fv = v - v0;

        float* row = descs.ptr<float>(i);
        float  len = 1e-8f;

        for (int d = 0; d < D; ++d) {
            float val = dp[d * Hd * Wd + v0 * Wd + u0] * (1 - fu) * (1 - fv)
                      + dp[d * Hd * Wd + v0 * Wd + u1] * fu       * (1 - fv)
                      + dp[d * Hd * Wd + v1 * Wd + u0] * (1 - fu) * fv
                      + dp[d * Hd * Wd + v1 * Wd + u1] * fu       * fv;
            row[d] = val;
            len   += val * val;
        }

        // L2-normalize the sampled descriptor in-place
        len = std::sqrt(len);
        for (int d = 0; d < D; ++d) row[d] /= len;
    }
}
