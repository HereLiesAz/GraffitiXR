// Host tests for the teleological-SLAM fixes: the partition fallback, growth surviving a
// repartition, the inlier spread riding with the pose, and a design move resetting corroboration.
#include <gtest/gtest.h>
#include <opencv2/opencv.hpp>
#include <cstring>
#include <jni.h>
#include "MobileGS.h"
#include "MobileGSTestPeer.h"

extern JavaVM* gJvm;

namespace {

const float kAnchor[16] = {0,1,0,0, -1,0,0,0, 0,0,1,0, 0.5f,-0.25f,-2.0f,1};
const float kView[16]   = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,-1.5f,1};
const float kIntr[4]    = {500.f, 500.f, 320.f, 240.f};

void seedMetric(MobileGS& g, const std::vector<uint8_t>& regions) {
    cv::Mat descs(4, 32, CV_8U, cv::Scalar(7));
    std::vector<cv::Point3f> pts = {{0,0,0}, {0.1f,0,0}, {0,0.1f,0}, {0.1f,0.1f,0}};
    g.restoreWallFingerprintMetric(descs, pts, kAnchor, kIntr, kView, regions);
    g.setWallPatch(cv::Mat(64, 64, CV_8UC1, cv::Scalar(200)));
}

// Deterministic, corner-rich "marks on a wall" (the same recipe MobileGSRelocTest uses).
cv::Mat makeWall(int w, int h, uint64_t seed = 0x9a11) {
    cv::RNG rng(seed);
    cv::Mat img(h, w, CV_8UC3, cv::Scalar(150, 150, 150));
    for (int i = 0; i < 160; ++i) {
        const int x = rng.uniform(0, w - 30), y = rng.uniform(0, h - 30);
        const int c = rng.uniform(0, 255);
        cv::rectangle(img, cv::Rect(x, y, rng.uniform(6, 30), rng.uniform(6, 30)),
                      cv::Scalar(c, 255 - c, (c * 7) % 255), cv::FILLED);
    }
    for (int i = 0; i < 60; ++i) {
        cv::line(img, cv::Point(rng.uniform(0, w), rng.uniform(0, h)),
                 cv::Point(rng.uniform(0, w), rng.uniform(0, h)), cv::Scalar(20, 20, 20), 2);
    }
    for (int i = 0; i < 80; ++i) {
        cv::circle(img, cv::Point(rng.uniform(0, w), rng.uniform(0, h)), rng.uniform(3, 9),
                   cv::Scalar(rng.uniform(0, 80), rng.uniform(0, 80), rng.uniform(0, 80)), cv::FILLED);
    }
    return img;
}

void fingerprintFrontalWall(MobileGS& g, const cv::Mat& img, const float K[4]) {
    const int dw = img.cols / 4, dh = img.rows / 4;
    std::vector<uint16_t> depth((size_t)dw * dh, 1000);
    const float view[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    g.generateFingerprint(img, cv::Mat(), reinterpret_cast<const uint8_t*>(depth.data()),
                          dw, dh, dw * (int)sizeof(uint16_t), K, view);
    ASSERT_GE(g.getWallKeypointCount(), 50) << "synthetic wall too featureless for the test";
}

} // namespace

// Marks the artist taps sit under the design they then place over them. With the partition tagging
// every one INSIDE there is no backbone, and relocalization used to stay at FEW_MATCHES for good (paint
// marks, which only appear after a lock, could never bootstrap). With almost no backbone the partition
// is now ignored for matching, so the stored marks localize until they are painted over.
TEST(MobileGSTeleological, AnAllInsidePartitionStillRelocalizes) {
    MobileGS g;
    g.initialize(0, 0);
    const cv::Mat wall = makeWall(800, 600);
    const float K[4] = {700.f, 700.f, 400.f, 300.f};
    fingerprintFrontalWall(g, wall, K);
    const int n = g.getWallKeypointCount();
    MobileGSTestPeer::setWallRegions(g, std::vector<uint8_t>((size_t)n, 0));   // all INSIDE
    g.setLiveIntrinsics(K);
    g.setEvalSyncReloc(true, 1);
    g.scheduleRelocCheck(wall);
    EXPECT_EQ(MobileGS::kRelocOk, g.lastRelocReject());
    EXPECT_EQ(0, g.lastRelocBackboneMatches()) << "none of these are backbone, and must not be reported as such";
    g.setEvalSyncReloc(false, 1);
    g.destroy();
}

// ...but with real backbone the partition still decides: INSIDE marks are not matched.
TEST(MobileGSTeleological, ARealBackboneStillExcludesTheInsideMarks) {
    MobileGS g;
    g.initialize(0, 0);
    const cv::Mat wall = makeWall(800, 600);
    const float K[4] = {700.f, 700.f, 400.f, 300.f};
    fingerprintFrontalWall(g, wall, K);
    const int n = g.getWallKeypointCount();
    std::vector<uint8_t> regions((size_t)n, 0);                                // all INSIDE ...
    for (int i = 0; i < n; i += 4) regions[(size_t)i] = 2;                    // ... but a quarter OUTSIDE
    MobileGSTestPeer::setWallRegions(g, regions);
    g.setLiveIntrinsics(K);
    g.setEvalSyncReloc(true, 1);
    g.scheduleRelocCheck(wall);
    ASSERT_EQ(MobileGS::kRelocOk, g.lastRelocReject());
    EXPECT_GT(g.lastRelocBackboneMatches(), 0);
    EXPECT_LT(g.lastRelocMatches(), n) << "INSIDE marks were matched despite a usable backbone";
    g.setEvalSyncReloc(false, 1);
    g.destroy();
}

// A repartition re-pushes only the BASE points, but the native set can be the base plus marks
// self-grow appended. That used to be read as a different fingerprint: the grown marks and the
// distortion head's patch were thrown away by the first design move.
TEST(MobileGSTeleological, RepartitionAfterGrowthKeepsTheGrownMarksAndThePatch) {
    MobileGS g;
    seedMetric(g, {0, 1, 2, 0});
    cv::Mat grownDescs(2, 32, CV_8U, cv::Scalar(9));
    MobileGSTestPeer::appendGrown(g, {{0.3f, 0, 0}, {0.3f, 0.1f, 0}}, grownDescs, 2);
    ASSERT_EQ(6u, MobileGSTestPeer::wallPointCount(g));

    cv::Mat descs(4, 32, CV_8U, cv::Scalar(7));
    std::vector<cv::Point3f> base = {{0,0,0}, {0.1f,0,0}, {0,0.1f,0}, {0.1f,0.1f,0}};
    g.restoreWallFingerprintMetric(descs, base, kAnchor, kIntr, kView, {2, 2, 0, 1});

    EXPECT_EQ(6u, MobileGSTestPeer::wallPointCount(g)) << "the grown marks were wiped by the repartition";
    EXPECT_EQ(6u, MobileGSTestPeer::wallRegionCount(g)) << "partition must stay 1:1 with the points";
    EXPECT_TRUE(MobileGSTestPeer::hasPatch(g)) << "the distortion head's patch was dropped";
}

TEST(MobileGSTeleological, AnActuallyDifferentFingerprintStillReplacesEverything) {
    MobileGS g;
    seedMetric(g, {0, 1, 2, 0});
    MobileGSTestPeer::appendGrown(g, {{0.3f, 0, 0}}, cv::Mat(1, 32, CV_8U, cv::Scalar(9)), 2);
    cv::Mat descs(4, 32, CV_8U, cv::Scalar(8));            // different content
    std::vector<cv::Point3f> pts = {{0,0,1}, {0.2f,0,1}, {0,0.2f,1}, {0.2f,0.2f,1}};
    g.restoreWallFingerprintMetric(descs, pts, kAnchor, kIntr, kView);
    EXPECT_EQ(4u, MobileGSTestPeer::wallPointCount(g));
    EXPECT_FALSE(MobileGSTestPeer::hasPatch(g));
}

// The solve's inlier spread is published with the pose, so fusion can refuse a clustered solve.
TEST(MobileGSTeleological, TheInlierSpreadRidesWithThePose) {
    MobileGS g;
    seedMetric(g, {});
    MobileGSTestPeer::setPnpSpread(g, 0.37f);
    float out[36];
    std::memset(out, 0, sizeof out);
    g.getRelocResult(out, true, true);
    EXPECT_FLOAT_EQ(0.37f, out[35]);
    // The legacy 35-float read must not touch slot 35.
    out[35] = -5.f;
    g.getRelocResult(out, true, false);
    EXPECT_FLOAT_EQ(-5.f, out[35]);
    g.clearWallFingerprint();
    g.getRelocResult(out, true, true);
    EXPECT_LT(out[35], 0.f) << "a cleared fingerprint's spread must read as not measured";
}

// Moving the design invalidates what was confirmed under the old placement; re-pushing the same
// placement (a load, a repartition) must not.
TEST(MobileGSTeleological, ADesignMoveResetsCorroborationButARepushDoesNot) {
    MobileGS g;
    float place[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,1,1};
    g.setDesignPlacement(place, 0.5f, 0.4f);
    MobileGSTestPeer::setCorroborated(g, {2, 2, 1, 0});
    g.setDesignPlacement(place, 0.5f, 0.4f);
    EXPECT_EQ((std::vector<uint8_t>{2, 2, 1, 0}), MobileGSTestPeer::corroborated(g));
    place[12] += 0.05f;                                     // nudged 5 cm
    g.setDesignPlacement(place, 0.5f, 0.4f);
    EXPECT_EQ((std::vector<uint8_t>{0, 0, 0, 0}), MobileGSTestPeer::corroborated(g));
    EXPECT_EQ((std::vector<uint8_t>{0, 0, 0, 0}), MobileGSTestPeer::promoted(g));
}

// ---- The persistent feature map ------------------------------------------------------------------

namespace {
// A map of [n] points on the z = 1 wall at distinct positions with distinct random descriptors.
void fillMap(MobileGS& g, int n, float conf, int obs, std::vector<cv::Point3f>& pts, cv::Mat& descs) {
    cv::RNG rng(0x5eed);
    descs = cv::Mat(n, 32, CV_8U);
    cv::randu(descs, 0, 256);
    pts.clear();
    for (int i = 0; i < n; ++i) pts.emplace_back(0.001f * (i % 100) - 0.05f, 0.001f * (i / 100) - 0.05f, 1.f);
    MobileGSTestPeer::setMap(g, pts, descs, std::vector<float>((size_t)n, conf), std::vector<int>((size_t)n, obs));
    (void)rng;
}
} // namespace

// A map point the camera predicts is in view but does not see again loses confidence. It used to
// only ever go up, so painted-over points stayed at their old confidence forever.
TEST(MobileGSTeleological, AMapPointInViewThatIsNotReSeenLosesConfidence) {
    MobileGS g;
    g.initialize(0, 0);   // creates the descriptor matchers the map association uses
    std::vector<cv::Point3f> wall;
    for (int i = 0; i <= 6; ++i) for (int j = 0; j <= 6; ++j) wall.emplace_back(-0.3f + 0.1f * i, -0.3f + 0.1f * j, 1.f);
    MobileGSTestPeer::setWallPoints(g, wall);
    std::vector<cv::Point3f> pts; cv::Mat descs;
    fillMap(g, 10, 0.8f, 3, pts, descs);
    // Features whose descriptors match nothing in the map.
    std::vector<cv::KeyPoint> kps{cv::KeyPoint(cv::Point2f(100.f, 100.f), 8.f), cv::KeyPoint(cv::Point2f(200.f, 120.f), 8.f)};
    cv::Mat live(2, 32, CV_8U);
    cv::randu(live, 0, 256);
    live.row(0).setTo(255); live.row(1).setTo(0);                  // far from the map's random rows
    MobileGSTestPeer::grow(g, glm::mat4(1.f), kps, live, 500, 500, 320, 240);
    const auto conf = MobileGSTestPeer::mapConfidence(g);
    ASSERT_GE(conf.size(), 10u);
    for (int i = 0; i < 10; ++i) EXPECT_LT(conf[(size_t)i], 0.8f) << "point " << i << " was in view and not re-seen";
    g.destroy();
}

// At the cap, fresh paint (a point seen once) must not be the first thing evicted, and points whose
// confidence has decayed to nothing must go first.
TEST(MobileGSTeleological, AtTheCapDeadPointsGoFirstAndFreshPaintIsKept) {
    MobileGS g;
    g.initialize(0, 0);
    MobileGSTestPeer::setWallPoints(g, std::vector<cv::Point3f>(20, cv::Point3f(0, 0, 1)));
    const int n = 5000;
    std::vector<cv::Point3f> pts; cv::Mat descs;
    fillMap(g, n, 0.9f, 4, pts, descs);                            // established points
    auto conf = MobileGSTestPeer::mapConfidence(g);
    auto obs = MobileGSTestPeer::mapObs(g);
    for (int i = 0; i < 100; ++i) { conf[(size_t)i] = 0.0f; }      // 100 dead
    for (int i = 100; i < 200; ++i) { conf[(size_t)i] = 0.1f; obs[(size_t)i] = 1; }   // 100 fresh
    MobileGSTestPeer::setMap(g, pts, descs, conf, obs);

    std::vector<cv::KeyPoint> kps{cv::KeyPoint(cv::Point2f(100.f, 100.f), 8.f), cv::KeyPoint(cv::Point2f(200.f, 120.f), 8.f)};
    cv::Mat live(2, 32, CV_8U);
    live.row(0).setTo(255); live.row(1).setTo(0);
    MobileGSTestPeer::grow(g, glm::mat4(1.f), kps, live, 500, 500, 320, 240);

    const auto after = MobileGSTestPeer::mapConfidence(g);
    const auto aobs = MobileGSTestPeer::mapObs(g);
    int fresh = 0, dead = 0;
    for (size_t i = 0; i < after.size(); ++i) {
        if (after[i] <= 0.02f) ++dead;
        if (aobs[i] == 1 && after[i] > 0.02f && after[i] < 0.2f) ++fresh;
    }
    EXPECT_EQ(0, dead) << "dead points should have been evicted";
    EXPECT_GE(fresh, 100) << "fresh paint was evicted ahead of established points";
    EXPECT_LE(after.size(), 5000u);
    g.destroy();
}

// ---- Paint-mark promotion --------------------------------------------------------------------------

namespace {
// Locks on the captured wall three times with the design placed exactly on it, which is enough
// for every design feature in view to be confirmed twice.
int paintMarksAfterThreeLocks(bool selfGrow) {
    MobileGS g;
    g.initialize(0, 0);
    const cv::Mat full = makeWall(800, 600, 0x51de);
    const float K[4] = {700.f, 700.f, 400.f, 300.f};
    fingerprintFrontalWall(g, full, K);
    g.setArtworkFingerprint(full, nullptr, 0, 0, 0, nullptr, nullptr);
    const float hw = (float)full.cols / (2.0f * K[0]);
    const float hh = (float)full.rows / (2.0f * K[1]);
    const float place[16] = {1,0,0,0, 0,-1,0,0, 0,0,-1,0,
                             (full.cols * 0.5f - K[2]) / K[0], (full.rows * 0.5f - K[3]) / K[1], 1.0f, 1};
    g.setDesignPlacement(place, hw, hh);
    g.setSelfGrowEnabled(selfGrow);
    g.setLiveIntrinsics(K);
    g.setEvalSyncReloc(true, 1);
    for (int i = 0; i < 3; ++i) g.scheduleRelocCheck(full);
    const int marks = g.getPaintMarkCount();
    g.setEvalSyncReloc(false, 1);
    g.destroy();
    return marks;
}
} // namespace

// Paint-mark promotion rewrites the reloc set, so it answers to the self-grow switch. It used to run
// ahead of the switch and of the trust gate.
TEST(MobileGSTeleological, PaintMarksAreNotPromotedWhileSelfGrowIsOff) {
    EXPECT_EQ(0, paintMarksAfterThreeLocks(false));
}

TEST(MobileGSTeleological, PaintMarksArePromotedFromAWellSpreadLockWhenSelfGrowIsOn) {
    EXPECT_GT(paintMarksAfterThreeLocks(true), 0);
}
