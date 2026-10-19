// Host tests for DepthScaleFit.h (port of SphereSLAM's DepthScaleFitTest) and its use in
// MobileGS::growMapFromReloc (off-wall radial placement).
#include <gtest/gtest.h>
#include <cmath>
#include <vector>
#include "DepthScaleFit.h"
#include "MobileGSTestPeer.h"

using graffitixr::fitDepthScale;

TEST(DepthScaleFit, RecoversAKnownAffineInverseDepthRelation) {
    const float a = 3.f, b = 0.5f;
    std::vector<float> x, y;
    for (float z : {0.5f, 1.f, 1.5f, 2.f, 3.f, 5.f, 8.f}) { x.push_back(1.f / z); y.push_back(a / z + b); }
    auto fit = fitDepthScale(x, y);
    ASSERT_TRUE(fit.ok);
    EXPECT_NEAR(fit.a, a, 1e-3f);
    EXPECT_NEAR(fit.b, b, 1e-3f);
    EXPECT_NEAR(fit.depthFor(a / 4.f + b), 4.f, 1e-2f);
}

TEST(DepthScaleFit, TooFewSamplesFails) {
    EXPECT_FALSE(fitDepthScale({1.f, 0.5f}, {1.f, 0.5f}).ok);
}

TEST(DepthScaleFit, DegenerateSameDepthFails) {
    EXPECT_FALSE(fitDepthScale(std::vector<float>(8, 0.5f), std::vector<float>(8, 2.f)).ok);
}

TEST(DepthScaleFit, InvertedSignIsRejected) {
    std::vector<float> x{0.2f, 0.4f, 0.6f, 0.8f, 1.0f, 1.2f, 1.4f}, y;
    for (float v : x) y.push_back(-2.f * v + 5.f);
    EXPECT_FALSE(fitDepthScale(x, y).ok);
}

TEST(DepthScaleFit, DepthForRejectsAtInfinityAndBehindCamera) {
    graffitixr::DepthFit fit; fit.a = 3.f; fit.b = 0.5f; fit.ok = true;
    EXPECT_LE(fit.depthFor(0.5f), 0.f);
    EXPECT_LE(fit.depthFor(0.1f), 0.f);
    EXPECT_GT(fit.depthFor(2.f), 0.f);
}

TEST(DepthScaleFit, GrossOutliersDoNotDragTheFit) {
    const float a = 3.f, b = 0.5f;
    std::vector<float> x, y;
    for (int i = 0; i < 20; ++i) { float z = 0.5f + i * 0.4f; x.push_back(1.f / z); y.push_back(a / z + b); }
    for (int i : {1, 4, 7, 10, 13, 16}) y[i] += (i % 2 == 0) ? 5.f : -3.f;
    auto fit = fitDepthScale(x, y);
    ASSERT_TRUE(fit.ok);
    EXPECT_NEAR(fit.a, a, 1e-3f);
    EXPECT_NEAR(fit.b, b, 1e-3f);
}

TEST(DepthScaleFit, DegeneracyCheckIsScaleInvariant) {
    std::vector<float> x, y;
    for (int i = 0; i < 8; ++i) { x.push_back(1e-4f * (1 + i)); y.push_back(2000.f * x.back() + 0.1f); }
    auto fit = fitDepthScale(x, y);
    ASSERT_TRUE(fit.ok);
    EXPECT_NEAR(fit.a, 2000.f, 1.f);
    std::vector<float> flat, fy;
    for (int i = 0; i < 8; ++i) { flat.push_back(1000.f + 1e-4f * i); fy.push_back(2.f * flat.back()); }
    EXPECT_FALSE(fitDepthScale(flat, fy).ok);
}

// Identity camera, K f=500 c=(320,240), wall plane z = 2 + 2x in the doodle frame. Wall features
// (u <= 400) intersect the plane in front; features at u >= 580 miss it (t < 0) and are placed only
// from depth. MiDaS reads invd = 3/Z + 0.5, the off-wall surface sits at Z = 3, and 30% of the wall
// samples are corrupted: a plain least-squares calibration misplaces the off-wall points.
TEST(MobileGSDepthPlacement, PlacesOffWallFeaturesAtTheirCalibratedDepthDespiteWallOutliers) {
    const double f = 500, cx = 320, cy = 240;
    const int W = 640, H = 480;
    const float a = 3.f, b = 0.5f, offWallZ = 3.f;
    MobileGS g;
    std::vector<cv::Point3f> wall;
    for (int i = 0; i <= 6; ++i) for (int j = 0; j <= 6; ++j) {
        float x = -0.5f + 0.1f * i, y = -0.3f + 0.1f * j;
        wall.emplace_back(x, y, 2.f + 2.f * x);
    }
    MobileGSTestPeer::setWallPoints(g, wall);

    std::vector<float> depth((size_t)W * H);
    for (int v = 0; v < H; ++v) for (int u = 0; u < W; ++u) {
        double up = (u - cx) / f;
        float z = (u >= 570) ? offWallZ : (float)(2.0 / (1.0 - 2.0 * up));
        depth[(size_t)v * W + u] = a / z + b;
    }
    std::vector<cv::KeyPoint> kps;
    for (int i = 0; i < 8; ++i) for (int j = 0; j < 5; ++j)
        kps.emplace_back(cv::Point2f(20.f + 50.f * i, 60.f + 80.f * j), 8.f);
    const size_t wallCount = kps.size();
    for (size_t k = 0; k < wallCount; k += 3) {   // corrupt every third wall sample (~33%)
        const auto& p = kps[k].pt;
        depth[(size_t)p.y * W + (size_t)p.x] += (k % 2 == 0) ? 4.f : -0.4f;
    }
    for (int j = 0; j < 5; ++j) kps.emplace_back(cv::Point2f(600.f, 60.f + 80.f * j), 8.f);

    cv::Mat descs((int)kps.size(), 32, CV_8U);
    cv::randu(descs, 0, 256);
    g.setLatestDepthMap(depth.data(), W, H, W, H);
    g.setDepthPlacementEnabled(true);
    MobileGSTestPeer::grow(g, glm::mat4(1.f), kps, descs, f, f, cx, cy);

    auto pts = MobileGSTestPeer::mapPoints(g);
    ASSERT_EQ(pts.size(), kps.size());
    for (size_t k = wallCount; k < kps.size(); ++k) {
        EXPECT_NEAR(pts[k].z, offWallZ, 0.01f * offWallZ) << "off-wall feature " << k;
        EXPECT_NEAR(pts[k].x, offWallZ * (600.f - cx) / f, 0.01f);
    }
}

TEST(MobileGSDepthPlacement, OffWallFeaturesAreDroppedWhenPlacementIsOff) {
    MobileGS g;
    std::vector<cv::Point3f> wall;
    for (int i = 0; i <= 6; ++i) for (int j = 0; j <= 6; ++j)
        wall.emplace_back(-0.5f + 0.1f * i, -0.3f + 0.1f * j, 2.f + 2.f * (-0.5f + 0.1f * i));
    MobileGSTestPeer::setWallPoints(g, wall);
    std::vector<float> depth(640 * 480, 1.5f);
    g.setLatestDepthMap(depth.data(), 640, 480, 640, 480);
    std::vector<cv::KeyPoint> kps{cv::KeyPoint(cv::Point2f(100.f, 240.f), 8.f),
                                  cv::KeyPoint(cv::Point2f(600.f, 240.f), 8.f)};
    cv::Mat descs(2, 32, CV_8U);
    cv::randu(descs, 0, 256);
    MobileGSTestPeer::grow(g, glm::mat4(1.f), kps, descs, 500, 500, 320, 240);
    EXPECT_EQ(MobileGSTestPeer::mapPoints(g).size(), 1u);
}
