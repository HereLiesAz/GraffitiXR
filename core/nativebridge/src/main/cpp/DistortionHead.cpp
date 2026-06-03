#include "include/DistortionHead.h"
#include <android/log.h>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "DistortionHead", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DistortionHead", __VA_ARGS__)

bool DistortionHead::load(const std::vector<uchar>& onnxBytes) {
    std::lock_guard<std::mutex> lock(mMutex);
    mLoaded = false;
    try {
        mNet = cv::dnn::readNetFromONNX(onnxBytes);
        mNet.setPreferableBackend(cv::dnn::DNN_BACKEND_DEFAULT);
        if (cv::ocl::haveOpenCL()) mNet.setPreferableTarget(cv::dnn::DNN_TARGET_OPENCL);
        else                       mNet.setPreferableTarget(cv::dnn::DNN_TARGET_CPU);
        mLoaded = !mNet.empty();
        if (mLoaded) LOGD("DistortionHead: loaded");
        return mLoaded;
    } catch (...) {
        LOGE("DistortionHead: failed to load ONNX");
        return false;
    }
}

bool DistortionHead::run(const cv::Mat& grayCur, const cv::Mat& grayFp, std::array<float, 13>& out) {
    if (!mLoaded || grayCur.empty() || grayFp.empty()) return false;

    cv::Mat cur, fp;
    cv::resize(grayCur, cur, cv::Size(kPatch, kPatch));
    cv::resize(grayFp, fp, cv::Size(kPatch, kPatch));
    cur.convertTo(cur, CV_32F, 1.0 / 255.0);
    fp.convertTo(fp, CV_32F, 1.0 / 255.0);
    cv::Mat blobCur = cv::dnn::blobFromImage(cur); // [1,1,256,256]
    cv::Mat blobFp  = cv::dnn::blobFromImage(fp);

    try {
        std::lock_guard<std::mutex> lock(mMutex);
        mNet.setInput(blobCur, "image_cur");
        mNet.setInput(blobFp, "image_fp");
        cv::Mat o = mNet.forward("distortion");
        if ((int)o.total() < 13) { LOGE("DistortionHead: bad output size %d", (int)o.total()); return false; }
        const float* p = o.ptr<float>(0);
        for (int i = 0; i < 13; ++i) out[i] = p[i];
        return true;
    } catch (const cv::Exception& e) {
        LOGE("DistortionHead: forward failed: %s", e.what());
        return false;
    }
}
