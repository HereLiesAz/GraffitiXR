// The bundled subject-isolation model (feature/editor assets/u2netp.onnx, run on device by
// SubjectIsolator through OpenCV's Java DNN) loads in OpenCV's ONNX importer and, with the same
// preprocessing (RGB, 320x320, ImageNet normalisation, first output, min-max), finds a subject.
#include <gtest/gtest.h>
#include <opencv2/opencv.hpp>
#include <opencv2/dnn.hpp>

#ifndef GRAFFITIXR_EDITOR_ASSETS
#define GRAFFITIXR_EDITOR_ASSETS ""
#endif

TEST(SubjectModel, TheBundledModelFindsASubjectOnAPlainGround) {
    const std::string path = std::string(GRAFFITIXR_EDITOR_ASSETS) + "/u2netp.onnx";
    cv::dnn::Net net = cv::dnn::readNetFromONNX(path);
    ASSERT_FALSE(net.empty());
    cv::Mat img(480, 640, CV_8UC3, cv::Scalar(40, 120, 40));
    cv::circle(img, {320, 240}, 120, cv::Scalar(30, 30, 220), cv::FILLED);
    cv::circle(img, {290, 210}, 30, cv::Scalar(250, 250, 250), cv::FILLED);
    cv::Mat rgb, in, f;
    cv::cvtColor(img, rgb, cv::COLOR_BGR2RGB);
    cv::resize(rgb, in, {320, 320}, 0, 0, cv::INTER_AREA);
    in.convertTo(f, CV_32FC3, 1.0 / 255.0);
    cv::subtract(f, cv::Scalar(0.485, 0.456, 0.406), f);
    cv::divide(f, cv::Scalar(0.229, 0.224, 0.225), f);
    net.setInput(cv::dnn::blobFromImage(f));
    std::vector<cv::Mat> outs;
    net.forward(outs, net.getUnconnectedOutLayersNames());
    ASSERT_FALSE(outs.empty());
    cv::Mat o(320, 320, CV_32F, outs[0].ptr<float>());
    double mn, mx; cv::minMaxLoc(o, &mn, &mx);
    ASSERT_GT(mx - mn, 1e-3);
    auto at = [&](int r, int c) { return (o.at<float>(r, c) - mn) / (mx - mn); };
    EXPECT_GT(at(160, 160), 0.5) << "the disc's centre is the subject";
    EXPECT_LT(at(8, 8), 0.5) << "the corner is background";
}
