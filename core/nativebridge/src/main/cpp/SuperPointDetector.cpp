// ~~~ FILE: ./core/nativebridge/src/main/cpp/SuperPointDetector.cpp ~~~
#include "include/SuperPointDetector.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "SuperPoint", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SuperPoint", __VA_ARGS__)

SuperPointDetector::InputGeometry SuperPointDetector::inputGeometry(int w, int h, int maxSide) {
    InputGeometry g;
    if (w <= 0 || h <= 0) return g;
    if (maxSide < 8) maxSide = 8;
    const int longest = std::max(w, h);
    const double s = longest > maxSide ? (double)maxSide / (double)longest : 1.0;
    g.resized = s < 1.0;
    g.scaledW = g.resized ? std::max(8, (int)std::lround(w * s)) : w;
    g.scaledH = g.resized ? std::max(8, (int)std::lround(h * s)) : h;
    g.netW = (g.scaledW + 7) / 8 * 8;
    g.netH = (g.scaledH + 7) / 8 * 8;
    // Per-axis only because rounding to whole pixels perturbs each axis by < 0.5 px; both equal
    // 1/s to within that rounding, i.e. the mapping back is uniform.
    g.scaleX = (float)w / (float)g.scaledW;
    g.scaleY = (float)h / (float)g.scaledH;
    return g;
}

bool SuperPointDetector::load(const std::vector<uchar>& onnxBytes) {
    std::lock_guard<std::mutex> lock(mMutex);
    mLoaded = false;
    try {
        mNet = cv::dnn::readNetFromONNX(onnxBytes);
        mNet.setPreferableBackend(cv::dnn::DNN_BACKEND_DEFAULT);
        if (cv::ocl::haveOpenCL()) {
            mNet.setPreferableTarget(cv::dnn::DNN_TARGET_OPENCL);
            LOGD("SuperPoint: using OpenCL backend");
        } else {
            mNet.setPreferableTarget(cv::dnn::DNN_TARGET_CPU);
            LOGD("SuperPoint: OpenCL unavailable, using CPU backend");
        }
        bool ok = !mNet.empty();
        mLoaded = ok;
        return ok;
    } catch (...) {
        return false;
    }
}

bool SuperPointDetector::detect(const cv::Mat& gray,
                                std::vector<cv::KeyPoint>& kps,
                                cv::Mat& descs,
                                float scoreThresh,
                                int   maxKps) {
    return detect(gray, kps, descs, cv::Mat(), scoreThresh, maxKps);
}

bool SuperPointDetector::detect(const cv::Mat& gray,
                                std::vector<cv::KeyPoint>& kps,
                                cv::Mat& descs,
                                const cv::Mat& mask,
                                float scoreThresh,
                                int   maxKps) {
    if (!mLoaded) return false;
    // Degenerate input: below one 8x8 network cell there is nothing to detect, and the 4-pixel
    // NMS border in extractKeypoints would leave no interior anyway. Rejected up front (before the
    // try block) so no OpenCV call is ever handed a zero-sized target.
    if (gray.empty() || gray.cols < 8 || gray.rows < 8) return false;
    std::lock_guard<std::mutex> lock(mMutex);
    if (mNet.empty()) return false;

    cv::Mat input;
    cv::Mat resizedMask;
    const InputGeometry geo = inputGeometry(gray.cols, gray.rows);
    const float scaleX = geo.scaleX, scaleY = geo.scaleY;

    try {
        // Aspect-preserving: ONE scale factor for both axes (the old path squashed every input
        // larger than 640x480 to exactly 640x480, so a portrait 480x640 frame was compressed 1.78x
        // horizontally and its descriptors were computed on a distorted wall). Then pad, rather
        // than resize, up to the network stride.
        cv::Mat content = gray;
        cv::Mat contentMask = mask;
        if (geo.resized) {
            cv::resize(gray, content, cv::Size(geo.scaledW, geo.scaledH), 0, 0, cv::INTER_AREA);
            if (!mask.empty())
                cv::resize(mask, contentMask, cv::Size(geo.scaledW, geo.scaledH), 0, 0, cv::INTER_NEAREST);
        }
        const int padR = geo.netW - geo.scaledW, padB = geo.netH - geo.scaledH;
        if (padR > 0 || padB > 0) {
            // Replicated, not black: a hard black edge is itself a strong corner the network would
            // fire on. Anything detected in the pad is dropped below regardless.
            cv::copyMakeBorder(content, input, 0, padB, 0, padR, cv::BORDER_REPLICATE);
            if (!contentMask.empty())
                cv::copyMakeBorder(contentMask, resizedMask, 0, padB, 0, padR, cv::BORDER_CONSTANT, cv::Scalar(0));
        } else {
            input = content;
            resizedMask = contentMask;
        }

        cv::Mat f;
        input.convertTo(f, CV_32F, 1.0 / 255.0);
        cv::Mat blob = cv::dnn::blobFromImage(f);

        mNet.setInput(blob);
        std::vector<cv::String> outNames = mNet.getUnconnectedOutLayersNames();
        std::vector<cv::Mat> outputs;
        mNet.forward(outputs, outNames);

        if (outputs.empty()) {
            LOGE("SuperPoint: forward() produced no outputs");
            return false;
        }

        const cv::Mat* semiPtr = nullptr;
        const cv::Mat* descPtr = nullptr;
        for (const cv::Mat& o : outputs) {
            if (o.dims < 4) continue;
            LOGD("SuperPoint: Output shape [%d, %d, %d, %d]", o.size[0], o.size[1], o.size[2], o.size[3]);
            if (o.size[1] == 65)  semiPtr = &o;
            if (o.size[1] == 256) descPtr = &o;
        }

        if (!semiPtr) LOGE("SuperPoint: heatmap output (65 channels) not found");
        if (!descPtr) LOGE("SuperPoint: descriptor output (256 channels) not found");

        if (!semiPtr || !descPtr) return false;

        kps.clear();
        // extractKeypoints sorts by score and truncates to its maxKps argument BEFORE any mask is
        // applied below. When a mask is present (generateFingerprint's isolated-marks path,
        // typically a few percent of the frame), requesting the caller's maxKps here means the
        // top-K is chosen from the WHOLE FRAME, and the mask then keeps only whichever of those
        // survive intersecting a small region -- often a handful, sometimes zero, silently
        // demoting the caller to its ORB fallback. Request an uncapped candidate set when masked,
        // so the mask filters the FULL detected set and the true top-K is chosen from what
        // actually survives inside the mask.
        const int extractCap = resizedMask.empty() ? maxKps : std::numeric_limits<int>::max();
        // Padding pixels are not part of the image. Uncapped when padding exists so the cap below
        // picks the top-K from real pixels only (same reasoning as the mask above).
        const bool padded = geo.netW != geo.scaledW || geo.netH != geo.scaledH;
        extractKeypoints(*semiPtr, kps, scoreThresh,
                         padded ? std::numeric_limits<int>::max() : extractCap);
        if (padded) {
            std::vector<cv::KeyPoint> inside;
            inside.reserve(kps.size());
            for (const auto& kp : kps)
                if (kp.pt.x < (float)geo.scaledW && kp.pt.y < (float)geo.scaledH) inside.push_back(kp);
            if (resizedMask.empty() && (int)inside.size() > maxKps) inside.resize(maxKps);
            kps = std::move(inside);
        }

        if (!resizedMask.empty()) {
            std::vector<cv::KeyPoint> filtered;
            for (const auto& kp : kps) {
                int ix = static_cast<int>(kp.pt.x);
                int iy = static_cast<int>(kp.pt.y);
                if (ix >= 0 && ix < resizedMask.cols && iy >= 0 && iy < resizedMask.rows) {
                    if (resizedMask.at<uchar>(iy, ix) > 0) filtered.push_back(kp);
                }
            }
            // extractKeypoints already sorted candidates by descending score; filtering by mask
            // preserves that relative order, so truncating here still keeps the best-scoring
            // survivors rather than an arbitrary subset.
            if ((int)filtered.size() > maxKps) filtered.resize(maxKps);
            kps = std::move(filtered);
        }

        if (kps.empty()) return false;

        // Sample descriptors while keypoints are still in network-input space
        // (the descriptor tensor is at input/8 resolution). Only after sampling
        // do we rescale the keypoints back to original image space for the caller.
        descs = cv::Mat();
        sampleDescriptors(*descPtr, kps, descs);

        if (scaleX != 1.0f || scaleY != 1.0f) {
            for (auto& kp : kps) { kp.pt.x *= scaleX; kp.pt.y *= scaleY; }
        }
        return true;
    } catch (...) {
        return false;
    }
}

bool SuperPointDetector::describe(const cv::Mat& gray, const std::vector<cv::Point2f>& pts, cv::Mat& descs) {
    if (!mLoaded || pts.empty()) return false;
    if (gray.empty() || gray.cols < 8 || gray.rows < 8) return false;
    std::lock_guard<std::mutex> lock(mMutex);
    if (mNet.empty()) return false;
    const InputGeometry geo = inputGeometry(gray.cols, gray.rows);
    try {
        cv::Mat content = gray, input;
        if (geo.resized) cv::resize(gray, content, cv::Size(geo.scaledW, geo.scaledH), 0, 0, cv::INTER_AREA);
        const int padR = geo.netW - geo.scaledW, padB = geo.netH - geo.scaledH;
        if (padR > 0 || padB > 0) cv::copyMakeBorder(content, input, 0, padB, 0, padR, cv::BORDER_REPLICATE);
        else input = content;
        cv::Mat f;
        input.convertTo(f, CV_32F, 1.0 / 255.0);
        mNet.setInput(cv::dnn::blobFromImage(f));
        std::vector<cv::String> outNames = mNet.getUnconnectedOutLayersNames();
        std::vector<cv::Mat> outputs;
        mNet.forward(outputs, outNames);
        const cv::Mat* descPtr = nullptr;
        for (const cv::Mat& o : outputs) if (o.dims >= 4 && o.size[1] == 256) descPtr = &o;
        if (!descPtr) return false;
        // Sampling happens in network-input space; the caller's points are in source pixels.
        std::vector<cv::KeyPoint> kps;
        kps.reserve(pts.size());
        for (const auto& p : pts) kps.emplace_back(p.x / geo.scaleX, p.y / geo.scaleY, 1.0f);
        descs = cv::Mat();
        sampleDescriptors(*descPtr, kps, descs);
        return descs.rows == (int)pts.size();
    } catch (...) {
        return false;
    }
}

void SuperPointDetector::extractKeypoints(const cv::Mat& semiTensor, std::vector<cv::KeyPoint>& kps, float thresh, int maxKps) {
    if (semiTensor.dims < 4) return;
    int Hc = semiTensor.size[2], Wc = semiTensor.size[3];
    const float* sp = (const float*)semiTensor.data;
    cv::Mat scores(Hc * 8, Wc * 8, CV_32F, cv::Scalar(0.0f));
    for (int r = 0; r < Hc; ++r) {
        for (int c = 0; c < Wc; ++c) {
            float vals[65];
            for (int k = 0; k < 65; ++k) vals[k] = sp[k * Hc * Wc + r * Wc + c];
            float mx = *std::max_element(vals, vals + 65), sum = 0.0f;
            for (int k = 0; k < 65; ++k) { vals[k] = std::exp(vals[k] - mx); sum += vals[k]; }
            for (int k = 0; k < 65; ++k) vals[k] /= sum;
            for (int dy = 0; dy < 8; ++dy)
                for (int dx = 0; dx < 8; ++dx)
                    scores.at<float>(r * 8 + dy, c * 8 + dx) = vals[dy * 8 + dx];
        }
    }
    std::vector<cv::KeyPoint> tmp;
    for (int r = 4; r < scores.rows - 4; ++r) {
        for (int c = 4; c < scores.cols - 4; ++c) {
            float v = scores.at<float>(r, c);
            if (v < thresh) continue;
            bool isMax = true;
            for (int dr = -4; dr <= 4 && isMax; ++dr)
                for (int dc = -4; dc <= 4 && isMax; ++dc)
                    // Strict >: with >=, an exact score tie between two adjacent pixels makes each
                    // see the other as >= itself, so BOTH lose local-max status and neither is
                    // emitted -- on an exactly-uniform heatmap this drops every keypoint. One pixel
                    // in a tied run should still win; which one is an arbitrary but harmless choice.
                    if (!(dr == 0 && dc == 0) && scores.at<float>(r + dr, c + dc) > v) isMax = false;
            if (isMax) tmp.push_back(cv::KeyPoint((float)c, (float)r, 1.0f, -1.0f, v));
        }
    }
    std::sort(tmp.begin(), tmp.end(), [](const cv::KeyPoint& a, const cv::KeyPoint& b) { return a.response > b.response; });
    if ((int)tmp.size() > maxKps) tmp.resize(maxKps);
    kps = std::move(tmp);
}

void SuperPointDetector::sampleDescriptors(const cv::Mat& descTensor, const std::vector<cv::KeyPoint>& kps, cv::Mat& descs) {
    if (descTensor.dims < 4 || kps.empty()) return;
    int D = descTensor.size[1], Hd = descTensor.size[2], Wd = descTensor.size[3];
    const float* dp = (const float*)descTensor.data;
    descs.create((int)kps.size(), D, CV_32F);
    for (int i = 0; i < (int)kps.size(); ++i) {
        // Clamp the float coordinates to the descriptor grid before deriving
        // indices/weights, so out-of-range keypoints sample the edge (clamp-to-edge)
        // instead of reading out of bounds or extrapolating with fu/fv outside [0,1].
        float u = std::max(0.0f, std::min(static_cast<float>(Wd - 1), kps[i].pt.x / 8.0f));
        float v = std::max(0.0f, std::min(static_cast<float>(Hd - 1), kps[i].pt.y / 8.0f));
        int u0 = (int)u, v0 = (int)v;
        int u1 = std::min(Wd - 1, u0 + 1), v1 = std::min(Hd - 1, v0 + 1);
        float fu = u - u0, fv = v - v0;
        float* row = descs.ptr<float>(i);
        float len = 1e-8f;
        for (int d = 0; d < D; ++d) {
            float val = dp[d * Hd * Wd + v0 * Wd + u0] * (1 - fu) * (1 - fv)
                      + dp[d * Hd * Wd + v0 * Wd + u1] * fu * (1 - fv)
                      + dp[d * Hd * Wd + v1 * Wd + u0] * (1 - fu) * fv
                      + dp[d * Hd * Wd + v1 * Wd + u1] * fu * fv;
            row[d] = val; len += val * val;
        }
        len = std::sqrt(len);
        for (int d = 0; d < D; ++d) row[d] /= len;
    }
}
