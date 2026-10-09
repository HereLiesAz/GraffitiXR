// Known-answer pose tests for HomographyTracker's CV->GL conversion (BACKLOG Phase 7).
//
// The live frame is SYNTHESISED from a chosen OpenGL-convention camera-from-plane pose using the
// GL pinhole model written out directly (u = cx + fx*x/-z, v = cy - fy*y/-z), then warped from the
// reference image. The tracker must recover that pose. The expectation never passes through
// OpenCV's camera convention or the diag(1,-1,-1) flip under test, so a sign error in that flip —
// the bug that once rendered every fallback overlay upside-down and back-facing — fails here.
#include <gtest/gtest.h>
#include <opencv2/opencv.hpp>
#include <cmath>
#include "HomographyTracker.h"

namespace {

constexpr int kW = 640, kH = 480;
constexpr float kFx = 500.f, kFy = 500.f, kCx = 320.f, kCy = 240.f;
constexpr float kHalfW = 1.0f, kHalfH = 0.75f;

cv::Mat texturedReference() {
    cv::Mat img(kH, kW, CV_8UC4, cv::Scalar(128, 128, 128, 255));
    cv::RNG rng(0x5eed);
    for (int i = 0; i < 400; ++i) {
        cv::Point a(rng.uniform(0, kW), rng.uniform(0, kH));
        cv::Point b(a.x + rng.uniform(8, 60), a.y + rng.uniform(8, 60));
        int g = rng.uniform(0, 256);
        cv::rectangle(img, a, b, cv::Scalar(g, g, g, 255), cv::FILLED);
    }
    for (int i = 0; i < 200; ++i) {
        int g = rng.uniform(0, 256);
        cv::circle(img, cv::Point(rng.uniform(0, kW), rng.uniform(0, kH)), rng.uniform(4, 25),
                   cv::Scalar(g, g, g, 255), cv::FILLED);
    }
    return img;
}

struct Pose { double R[3][3]; double t[3]; };

Pose rotYX(double yawDeg, double pitchDeg, double tx, double ty, double tz) {
    const double y = yawDeg * M_PI / 180.0, p = pitchDeg * M_PI / 180.0;
    const double Ry[3][3] = {{std::cos(y), 0, std::sin(y)}, {0, 1, 0}, {-std::sin(y), 0, std::cos(y)}};
    const double Rx[3][3] = {{1, 0, 0}, {0, std::cos(p), -std::sin(p)}, {0, std::sin(p), std::cos(p)}};
    Pose out{};
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) {
            double s = 0;
            for (int k = 0; k < 3; ++k) s += Ry[r][k] * Rx[k][c];
            out.R[r][c] = s;
        }
    out.t[0] = tx; out.t[1] = ty; out.t[2] = tz;
    return out;
}

/** Reference pixel -> plane point -> GL camera -> live pixel, written out longhand. */
cv::Point2f project(const Pose& P, float refU, float refV) {
    const double X = (refU / kW - 0.5) * 2.0 * kHalfW;
    const double Y = -(refV / kH - 0.5) * 2.0 * kHalfH;
    const double x = P.R[0][0] * X + P.R[0][1] * Y + P.t[0];
    const double y = P.R[1][0] * X + P.R[1][1] * Y + P.t[1];
    const double z = P.R[2][0] * X + P.R[2][1] * Y + P.t[2];
    return {static_cast<float>(kCx + kFx * x / -z), static_cast<float>(kCy - kFy * y / -z)};
}

cv::Mat synthesiseFrame(const cv::Mat& ref, const Pose& P) {
    std::vector<cv::Point2f> src = {{0, 0}, {kW, 0}, {kW, kH}, {0, kH}};
    std::vector<cv::Point2f> dst;
    for (const auto& s : src) dst.push_back(project(P, s.x, s.y));
    cv::Mat H = cv::getPerspectiveTransform(src, dst);
    cv::Mat frame;
    cv::warpPerspective(ref, frame, H, cv::Size(kW, kH), cv::INTER_LINEAR, cv::BORDER_CONSTANT,
                        cv::Scalar(0, 0, 0, 255));
    return frame;
}

void expectPose(const float* m, const Pose& P, double rotTol, double transTol) {
    // Column-major: m[col*4 + row].
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c)
            EXPECT_NEAR(m[c * 4 + r], P.R[r][c], rotTol) << "R[" << r << "][" << c << "]";
    for (int r = 0; r < 3; ++r) EXPECT_NEAR(m[12 + r], P.t[r], transTol) << "t[" << r << "]";
    EXPECT_FLOAT_EQ(m[15], 1.0f);
}

} // namespace

TEST(HomographyTrackerKnownAnswer, FrontalPlaneRecoversIdentityRotation) {
    HomographyTracker tracker;
    const cv::Mat ref = texturedReference();
    ASSERT_TRUE(tracker.setReference(ref, kHalfW, kHalfH));
    const Pose P = rotYX(0, 0, 0, 0, -2.5);
    float pose[16]; float conf = -1;
    ASSERT_TRUE(tracker.track(synthesiseFrame(ref, P), kFx, kFy, kCx, kCy, pose, &conf));
    expectPose(pose, P, 0.03, 0.05);
    EXPECT_GT(conf, HomographyTracker::kMinInlierRatio);
    // Spelled out for the historical bug: the design's up (+Y column) points up on screen, and its
    // normal (+Z column) faces the camera. The C·R·C double flip negated both.
    EXPECT_GT(pose[5], 0.9f);
    EXPECT_GT(pose[10], 0.9f);
}

TEST(HomographyTrackerKnownAnswer, TiltedAndOffsetPlaneRecoversFullPose) {
    HomographyTracker tracker;
    const cv::Mat ref = texturedReference();
    ASSERT_TRUE(tracker.setReference(ref, kHalfW, kHalfH));
    const Pose P = rotYX(25, -15, 0.2, -0.1, -2.8);
    float pose[16]; float conf = -1;
    ASSERT_TRUE(tracker.track(synthesiseFrame(ref, P), kFx, kFy, kCx, kCy, pose, &conf));
    expectPose(pose, P, 0.03, 0.06);
}

TEST(HomographyTrackerKnownAnswer, FailedTrackLeavesOutputsUntouched) {
    HomographyTracker tracker;
    ASSERT_TRUE(tracker.setReference(texturedReference(), kHalfW, kHalfH));
    float pose[16]; for (float& v : pose) v = 42.f;
    float conf = 42.f;
    const cv::Mat blank(kH, kW, CV_8UC4, cv::Scalar(90, 90, 90, 255));
    EXPECT_FALSE(tracker.track(blank, kFx, kFy, kCx, kCy, pose, &conf));
    EXPECT_FLOAT_EQ(pose[0], 42.f);
    EXPECT_FLOAT_EQ(conf, 42.f);
}

TEST(HomographyTrackerKnownAnswer, PlainReferenceIsRefused) {
    HomographyTracker tracker;
    EXPECT_FALSE(tracker.setReference(cv::Mat(kH, kW, CV_8UC4, cv::Scalar(128, 128, 128, 255)), kHalfW, kHalfH));
    EXPECT_FALSE(tracker.hasReference());
}
