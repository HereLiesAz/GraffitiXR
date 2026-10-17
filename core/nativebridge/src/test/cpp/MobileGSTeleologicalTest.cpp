// Host tests for the teleological-SLAM fixes: the partition fallback, growth surviving a
// repartition, the inlier spread riding with the pose, and a design move resetting corroboration.
#include <set>
#include <gtest/gtest.h>
#include <opencv2/opencv.hpp>
#include <cstring>
#include <jni.h>
#include "MobileGS.h"
#include "MobileGSTestPeer.h"
#include "ShapeMatch.h"
#include <algorithm>

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
    g.awaitFilterTargets();   // the filters' targets are built on a worker
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

// ---- Pass evidence ---------------------------------------------------------------------------------

// The cell is confirmed when ANY pass sees paint: the passes fail differently, so a dissenting pass
// must not veto another's evidence.
TEST(PassEvidence, AnyPresentVoteConfirmsAndNoDataOrAbsentDoesNot) {
    using namespace passes;
    const uint8_t none[2] = {kNoData, kNoData};
    const uint8_t absent[2] = {kAbsent, kAbsent};
    const uint8_t mixed[2] = {kAbsent, kPresent};
    const uint8_t one[2] = {kPresent, kNoData};
    EXPECT_FALSE(combineVotes(none, 2));
    EXPECT_FALSE(combineVotes(absent, 2));
    EXPECT_TRUE(combineVotes(mixed, 2)) << "a dissenting pass vetoed another's evidence";
    EXPECT_TRUE(combineVotes(one, 2));
    EXPECT_FALSE(combineVotes(none, 0));
}

TEST(PassEvidence, EveryPassHasAStableName) {
    EXPECT_STREQ("features", passes::passName(passes::kPassFeatures));
    EXPECT_STREQ("colour", passes::passName(passes::kPassColour));
    EXPECT_STREQ("unknown", passes::passName(99));
    for (int f = 0; f < designfilters::kCount; ++f)
        EXPECT_STREQ(designfilters::name(f), passes::passName(passes::kPassFilterBase + f));
}

// ---- Design filter bank ------------------------------------------------------------------------------

TEST(DesignFilters, EveryFilterReturnsAGreyPictureTheSizeOfTheDesign) {
    const cv::Mat wall = makeWall(640, 480, 0x77);
    cv::Mat design;
    if (wall.channels() == 1) design = wall; else cv::cvtColor(wall, design, wall.channels() == 4 ? cv::COLOR_RGBA2GRAY : cv::COLOR_RGB2GRAY);
    for (int ch : {1, 3, 4}) {
        cv::Mat d;
        if (ch == 1) d = design;
        else if (ch == 3) cv::cvtColor(design, d, cv::COLOR_GRAY2RGB);
        else cv::cvtColor(design, d, cv::COLOR_GRAY2RGBA);
        for (int f = 0; f < designfilters::kCount; ++f) {
            const cv::Mat out = designfilters::apply(f, d);
            ASSERT_FALSE(out.empty()) << designfilters::name(f) << " ch=" << ch;
            EXPECT_EQ(CV_8UC1, out.type()) << designfilters::name(f);
            EXPECT_EQ(d.size(), out.size()) << designfilters::name(f);
        }
    }
    EXPECT_TRUE(designfilters::apply(designfilters::kCount, design).empty());
    EXPECT_TRUE(designfilters::apply(-1, design).empty());
    EXPECT_TRUE(designfilters::apply(designfilters::kSketch, cv::Mat()).empty());
}

TEST(DesignFilters, PosterizeKeepsTwoAndFourTones) {
    const cv::Mat design = makeWall(320, 240, 0x31);
    for (auto [id, tones] : {std::pair{designfilters::kPosterize2, 2}, std::pair{designfilters::kPosterize4, 4}}) {
        const cv::Mat out = designfilters::apply(id, design);
        std::set<int> seen;
        for (int y = 0; y < out.rows; ++y) for (int x = 0; x < out.cols; ++x) seen.insert(out.at<uchar>(y, x));
        EXPECT_LE((int)seen.size(), tones) << designfilters::name(id);
        EXPECT_GE((int)seen.size(), 2) << designfilters::name(id);
    }
}

TEST(DesignFilters, StencilLayersHaveAtMostTheRequestedTonesAndAreRepeatable) {
    const cv::Mat design = makeWall(640, 480, 0x31);
    for (int tones = 2; tones <= 5; ++tones) {
        const cv::Mat a = designfilters::stencilLayers(design, tones);
        const cv::Mat b = designfilters::stencilLayers(design, tones);
        ASSERT_FALSE(a.empty());
        EXPECT_EQ(design.size(), a.size());
        EXPECT_EQ(0, cv::countNonZero(a != b)) << "same design, different layers (tones=" << tones << ")";
        std::set<int> seen;
        for (int y = 0; y < a.rows; ++y) for (int x = 0; x < a.cols; ++x) seen.insert(a.at<uchar>(y, x));
        EXPECT_LE((int)seen.size(), tones);
        EXPECT_GE((int)seen.size(), 2);
    }
    EXPECT_TRUE(designfilters::stencilLayers(design, 1).empty());
    EXPECT_TRUE(designfilters::stencilLayers(cv::Mat(), 3).empty());
}

TEST(DesignFilters, TheBuildOrderIsEveryFilterOnceAndTheCheapOnesFirst) {
    std::set<int> seen(std::begin(designfilters::kBuildOrder), std::end(designfilters::kBuildOrder));
    EXPECT_EQ((size_t)designfilters::kCount, seen.size());
    for (int f = 0; f < designfilters::kCount; ++f) EXPECT_EQ(1u, seen.count(f)) << designfilters::name(f);
    auto position = [](int id) {
        return (int)(std::find(std::begin(designfilters::kBuildOrder), std::end(designfilters::kBuildOrder), id) -
                     std::begin(designfilters::kBuildOrder));
    };
    EXPECT_LT(position(designfilters::kInvert), position(designfilters::kSketch));
    EXPECT_LT(position(designfilters::kPosterize2), position(designfilters::kCartoon));
    EXPECT_EQ(designfilters::kCount - 1, position(designfilters::kStencil3)) << "the stencil is the slowest, so it is last";
}

TEST(MobileGSTeleological, FilterTargetsAreBuiltOffTheCallingThreadAndSupersededBuildsStandDown) {
    MobileGS g;
    g.initialize(0, 0);
    const float K[4] = {700.f, 700.f, 400.f, 300.f};
    const cv::Mat wall = makeWall(800, 600, 0x51de);
    fingerprintFrontalWall(g, wall, K);
    // Two designs in a row: the first build must stand down for the second, never install its places.
    g.setArtworkFingerprint(makeWall(800, 600, 0x1111), nullptr, 0, 0, 0, nullptr, nullptr);
    g.setArtworkFingerprint(makeWall(800, 600, 0x2222), nullptr, 0, 0, 0, nullptr, nullptr);
    g.awaitFilterTargets();
    EXPECT_GE(g.readyFilterTargets(), 4);
    // Clearing the wall drops the targets with the design.
    g.clearWallFingerprint();
    g.awaitFilterTargets();
    EXPECT_EQ(0, g.readyFilterTargets());
    g.destroy();
}

TEST(DesignFilters, InvertIsTheNegative) {
    const cv::Mat design = makeWall(160, 120, 0x12);
    cv::Mat grey;
    if (design.channels() == 1) grey = design; else cv::cvtColor(design, grey, cv::COLOR_RGB2GRAY);
    const cv::Mat out = designfilters::apply(designfilters::kInvert, design);
    EXPECT_EQ(0, cv::countNonZero(out + grey - 255));
}

TEST(DesignFilters, LocationsStayInsideTheBorderAndWithinTheCap) {
    const cv::Mat design = makeWall(640, 480, 0x77);
    for (int f = 0; f < designfilters::kCount; ++f) {
        const cv::Mat out = designfilters::apply(f, design);
        const auto pts = designfilters::locations(out, 100, 32);
        EXPECT_LE(pts.size(), 100u) << designfilters::name(f);
        for (const auto& p : pts) {
            EXPECT_GE(p.x, 32.f); EXPECT_GE(p.y, 32.f);
            EXPECT_LT(p.x, 640.f - 32.f); EXPECT_LT(p.y, 480.f - 32.f);
        }
    }
    EXPECT_TRUE(designfilters::locations(cv::Mat(), 10).empty());
    EXPECT_TRUE(designfilters::locations(cv::Mat(40, 40, CV_8UC1, cv::Scalar(0)), 10, 32).empty());
}

// With the design laid exactly on the captured wall, the feature pass judges cells and sees paint in
// them, and the numbers show up in the per-pass stats. An unknown pass is refused.
TEST(MobileGSTeleological, ThePassStatsCountWhatEachPassJudged) {
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
    g.setLiveIntrinsics(K);
    g.setEvalSyncReloc(true, 1);
    for (int i = 0; i < 3; ++i) g.scheduleRelocCheck(full);

    long st[5];
    ASSERT_TRUE(g.getPassStats(passes::kPassFeatures, st));
    EXPECT_GT(st[0], 0) << "the feature pass never judged a tick";
    EXPECT_GT(st[1], 0) << "the feature pass never judged a cell";
    EXPECT_GT(st[2], 0) << "the feature pass never saw paint on a wall that matches the design";
    EXPECT_LE(st[2], st[1]);
    long colour[5];
    ASSERT_TRUE(g.getPassStats(passes::kPassColour, colour));
    // The filter passes run too, all the time, beside the first two.
    long filterJudged = 0, filterSeen = 0;
    int setsWithRows = 0;
    for (int f = 0; f < designfilters::kCount; ++f) {
        if (MobileGSTestPeer::targetSetRows(g, f) > 0) ++setsWithRows;
        long fs[5];
        ASSERT_TRUE(g.getPassStats(passes::kPassFilterBase + f, fs));
        filterJudged += fs[1]; filterSeen += fs[2];
        EXPECT_LE(fs[2], fs[1]);
    }
    EXPECT_GE(setsWithRows, 4) << "most design filters found nothing to look at on a textured design";
    EXPECT_GT(filterJudged, 0) << "no filter pass ever judged a cell";
    EXPECT_GT(filterSeen, 0) << "no filter pass saw paint on a wall that matches the design";
    EXPECT_FALSE(g.getPassStats(passes::kPassCount, st));
    EXPECT_FALSE(g.getPassStats(-1, st));
    g.setEvalSyncReloc(false, 1);
    g.destroy();
}

// ---- Wall exclusion list and baseline margin ---------------------------------------------------------

namespace {
struct PaintedRig {
    MobileGS g;
    cv::Mat bare, design, live;
    float K[4] = {700.f, 700.f, 400.f, 300.f};
    // A bare wall captured, a different design registered over it, and a live frame that is the wall
    // with the design painted in the middle ([painted] false: the bare wall still).
    void build(bool painted) {
        g.initialize(0, 0);
        bare = makeWall(800, 600, 0x51de);
        design = makeWall(800, 600, 0xbeef);
        fingerprintFrontalWall(g, bare, K);
        g.setArtworkFingerprint(design, nullptr, 0, 0, 0, nullptr, nullptr);
        const float hw = (float)bare.cols / (2.0f * K[0]);
        const float hh = (float)bare.rows / (2.0f * K[1]);
        const float place[16] = {1,0,0,0, 0,-1,0,0, 0,0,-1,0,
                                 (bare.cols * 0.5f - K[2]) / K[0], (bare.rows * 0.5f - K[3]) / K[1], 1.0f, 1};
        g.setDesignPlacement(place, hw, hh);
        live = bare.clone();
        if (painted) {
            const cv::Rect mid(150, 100, 500, 400);
            design(mid).copyTo(live(mid));
        }
        g.setLiveIntrinsics(K);
        g.setEvalSyncReloc(true, 1);
        g.scheduleRelocCheck(live);
    }
    ~PaintedRig() { g.setEvalSyncReloc(false, 1); g.destroy(); }
};
} // namespace

TEST(MobileGSTeleological, PaintedDesignCorroboratesAndTheBareWallDoesNot) {
    PaintedRig painted; painted.build(true);
    ASSERT_EQ(MobileGS::kRelocOk, painted.g.lastRelocReject());
    ASSERT_EQ(MobileGS::kCorrobGated, painted.g.corrobGateReason());
    PaintedRig bare; bare.build(false);
    ASSERT_EQ(MobileGS::kRelocOk, bare.g.lastRelocReject());
    ASSERT_EQ(MobileGS::kCorrobGated, bare.g.corrobGateReason());
    std::fprintf(stderr, "painted matched %d / %d, bare matched %d / %d\n",
                 painted.g.corrobMatched(), painted.g.corrobPredicted(),
                 bare.g.corrobMatched(), bare.g.corrobPredicted());
    EXPECT_GT(painted.g.corrobMatched(), 20) << "design painted on the wall did not corroborate";
    EXPECT_LE(bare.g.corrobMatched() * 4, painted.g.corrobMatched()) << "the bare wall corroborated like paint";
}

// A design that repeats itself at the baseline's displacement agrees with the wall just as well at the
// displaced placements as at the true one: that is chance-level agreement, not evidence, and the pass
// must not count it. (Tile 120x90 px = the baseline's shift of 30% of the design's half-extent.)
TEST(MobileGSTeleological, ARepeatingDesignFailsTheBaselineMargin) {
    MobileGS g;
    g.initialize(0, 0);
    const float K[4] = {700.f, 700.f, 400.f, 300.f};
    const cv::Mat bare = makeWall(800, 600, 0x51de);
    const cv::Mat tile = makeWall(120, 90, 0x7157);
    cv::Mat design(600, 800, CV_8UC3);
    for (int y = 0; y < 600; y += 90) for (int x = 0; x < 800; x += 120) {
        const cv::Rect r(x, y, std::min(120, 800 - x), std::min(90, 600 - y));
        tile(cv::Rect(0, 0, r.width, r.height)).copyTo(design(r));
    }
    fingerprintFrontalWall(g, bare, K);
    g.setArtworkFingerprint(design, nullptr, 0, 0, 0, nullptr, nullptr);
    const float hw = 800.f / (2.0f * K[0]), hh = 600.f / (2.0f * K[1]);
    const float place[16] = {1,0,0,0, 0,-1,0,0, 0,0,-1,0, 0.0f, 0.0f, 1.0f, 1};
    g.setDesignPlacement(place, hw, hh);
    cv::Mat live = bare.clone();
    const cv::Rect mid(150, 90, 480, 360);
    design(mid).copyTo(live(mid));
    g.setLiveIntrinsics(K);
    g.setEvalSyncReloc(true, 1);
    g.scheduleRelocCheck(live);
    ASSERT_EQ(MobileGS::kRelocOk, g.lastRelocReject());
    ASSERT_EQ(MobileGS::kCorrobGated, g.corrobGateReason());
    ASSERT_GT(g.corrobPredicted(), 50);
    EXPECT_EQ(0, g.corrobMatched()) << "a design that matches itself at displaced placements counted as evidence";
    g.setEvalSyncReloc(false, 1);
    g.destroy();
}

// ---- Pass B: change against the clean wall, judged by shape ------------------------------------------

namespace {
// An 8 x 4 grid whose left half is design patch 0 and right half patch 1; nothing is outside the design.
const int kCols = 8, kRows = 4;
std::vector<int> twoPatches() {
    std::vector<int> p((size_t)kCols * kRows);
    for (int i = 0; i < kCols * kRows; ++i) p[(size_t)i] = (i % kCols) < kCols / 2 ? 0 : 1;
    return p;
}
std::vector<uint8_t> allJudged() { return std::vector<uint8_t>((size_t)kCols * kRows, 1); }
std::vector<uint8_t> cellsWhere(bool (*pred)(int x, int y)) {
    std::vector<uint8_t> v((size_t)kCols * kRows, 0);
    for (int i = 0; i < kCols * kRows; ++i) v[(size_t)i] = pred(i % kCols, i / kCols) ? 1 : 0;
    return v;
}
int presentCount(const std::vector<uint8_t>& v) {
    return (int)std::count(v.begin(), v.end(), (uint8_t)passes::kPresent);
}
} // namespace

TEST(ShapeMatch, LabelsConnectedCellsOfTheSameKey) {
    // 0 0 1 1
    // 0 2 1 1   key 2 touches nothing of its own kind; -1 is outside
    // 0 0 -1 0
    const std::vector<int> key = {0, 0, 1, 1,  0, 2, 1, 1,  0, 0, -1, 0};
    const auto l = shapematch::label(key, 4, 3);
    EXPECT_EQ(l[0], l[1]);
    EXPECT_EQ(l[0], l[4]);
    EXPECT_EQ(l[0], l[8]);
    EXPECT_EQ(l[2], l[3]);
    EXPECT_NE(l[0], l[2]);
    EXPECT_NE(l[5], l[0]);
    EXPECT_EQ(-1, l[10]);
    EXPECT_NE(l[11], l[0]) << "a same-key cell cut off by an outside cell is its own patch";
}

TEST(ShapeMatch, ACompletePatchIsPaintAndTheUntouchedOneIsNot) {
    const auto v = shapematch::verdicts(cellsWhere([](int x, int) { return x < kCols / 2; }), allJudged(), twoPatches(), kCols, kRows);
    EXPECT_EQ(kRows * kCols / 2, presentCount(v));
    for (int i = 0; i < kCols * kRows; ++i)
        EXPECT_EQ((i % kCols) < kCols / 2 ? passes::kPresent : passes::kAbsent, v[(size_t)i]) << "cell " << i;
}

TEST(ShapeMatch, TheWholeDesignPaintedAtOnceIsPaint) {
    const auto v = shapematch::verdicts(cellsWhere([](int, int) { return true; }), allJudged(), twoPatches(), kCols, kRows);
    EXPECT_EQ(kCols * kRows, presentCount(v));
}

TEST(ShapeMatch, PaintInProgressIsPaint) {
    // The right patch complete, the left half done (one partial patch).
    const auto v = shapematch::verdicts(
        cellsWhere([](int x, int y) { return x >= kCols / 2 || y < kRows / 2; }), allJudged(), twoPatches(), kCols, kRows);
    EXPECT_EQ(kCols * kRows - kCols / 2 * (kRows / 2), presentCount(v));
}

TEST(ShapeMatch, ABandCuttingAcrossManyPatchesIsNotPaint) {
    // Four column patches; a band over the top half of every one is four partial patches: a shadow.
    std::vector<int> patch((size_t)kCols * kRows);
    for (int i = 0; i < kCols * kRows; ++i) patch[(size_t)i] = (i % kCols) / 2;
    const auto v = shapematch::verdicts(cellsWhere([](int, int y) { return y < kRows / 2; }), allJudged(), patch, kCols, kRows);
    EXPECT_EQ(0, presentCount(v));
}

TEST(ShapeMatch, AChangeOutsideTheDesignIsNotPaint) {
    // The right half is not design at all (patch -1); a hand over it, plus a sliver of the left patch.
    auto patch = twoPatches();
    for (int i = 0; i < kCols * kRows; ++i) if ((i % kCols) >= kCols / 2) patch[(size_t)i] = -1;
    const auto v = shapematch::verdicts(cellsWhere([](int x, int y) { return x >= kCols / 2 - 1 && y < kRows / 2; }),
                                        allJudged(), patch, kCols, kRows);
    EXPECT_EQ(0, presentCount(v));
}

TEST(ShapeMatch, ASingleChangedCellInABigPatchIsNoise) {
    const auto v = shapematch::verdicts(cellsWhere([](int x, int y) { return x == 0 && y == 0; }),
                                        allJudged(), twoPatches(), kCols, kRows);
    EXPECT_EQ(0, presentCount(v));
}

TEST(ShapeMatch, CellsThatCouldNotBeComparedHaveNoVerdict) {
    auto judged = allJudged();
    judged[0] = 0;
    const auto v = shapematch::verdicts(cellsWhere([](int, int) { return true; }), judged, twoPatches(), kCols, kRows);
    EXPECT_EQ(passes::kNoData, v[0]);
    EXPECT_EQ(passes::kPresent, v[1]);
}

// The pass reads the clean-wall photo, so it needs one registered, and the Settings switch on.
namespace {
struct ChangeRig {
    MobileGS g;
    cv::Mat bare, live;
    float K[4] = {700.f, 700.f, 400.f, 300.f};
    bool registered = false;
    long judged = 0, seen = 0;

    void build(bool enable, bool paintLeftHalf, bool giveCleanPhoto) {
        g.initialize(0, 0);
        bare = makeWall(800, 600, 0x51de);
        // Two colour fields, left red and right blue, each with a few small dark and light marks (a
        // design needs corners to register at all); the marks barely move a cell's mean colour.
        cv::Mat design(600, 800, CV_8UC3);
        design(cv::Rect(0, 0, 400, 600)).setTo(cv::Scalar(30, 30, 200));
        design(cv::Rect(400, 0, 400, 600)).setTo(cv::Scalar(200, 60, 30));
        cv::RNG rng(0x9e1);
        for (int i = 0; i < 90; ++i) {
            const int x = rng.uniform(10, 770), y = rng.uniform(10, 570);
            cv::rectangle(design, cv::Rect(x, y, 9, 9), (i % 2) ? cv::Scalar(20, 20, 20) : cv::Scalar(235, 235, 235), cv::FILLED);
        }
        fingerprintFrontalWall(g, bare, K);
        g.setCaptureImage(bare, K);
        g.setArtworkFingerprint(design, nullptr, 0, 0, 0, nullptr, nullptr);
        g.awaitFilterTargets();
        const float hw = 800.f / (2.0f * K[0]), hh = 600.f / (2.0f * K[1]);
        const float place[16] = {1,0,0,0, 0,-1,0,0, 0,0,-1,0, 0.0f, 0.0f, 1.0f, 1};
        g.setDesignPlacement(place, hw, hh);
        if (giveCleanPhoto) registered = g.setCleanWallImage(bare);
        g.setChangePassEnabled(enable);
        live = bare.clone();
        // Paint the left half in a colour the design does NOT use: the pass cares about shape only.
        if (paintLeftHalf) live(cv::Rect(0, 0, 400, 600)).setTo(cv::Scalar(40, 200, 40));
        g.setLiveIntrinsics(K);
        g.setEvalSyncReloc(true, 1);
        for (int i = 0; i < 4; ++i) g.scheduleRelocCheck(live);
        long st[5];
        g.getPassStats(passes::kPassChange, st);
        judged = st[1]; seen = st[2];
    }
    ~ChangeRig() { g.setEvalSyncReloc(false, 1); g.destroy(); }
};
} // namespace

TEST(MobileGSTeleological, TheChangePassIsOffUntilTheSwitchIsOn) {
    MobileGS g;
    EXPECT_FALSE(g.changePassEnabled());
    ChangeRig rig; rig.build(/*enable=*/false, /*paint=*/true, /*clean=*/true);
    EXPECT_TRUE(rig.registered);
    EXPECT_EQ(0, rig.judged) << "the pass ran with its switch off";
}

TEST(MobileGSTeleological, TheChangePassCannotRunWithoutTheCleanWallPhoto) {
    ChangeRig rig; rig.build(/*enable=*/true, /*paint=*/true, /*clean=*/false);
    EXPECT_EQ(MobileGS::kCleanNone, rig.g.cleanWallStatus());
    EXPECT_EQ(0, rig.judged) << "the pass ran with no clean-wall photo";
}

TEST(MobileGSTeleological, ThePassSeesAPatchPaintedInAColourTheDesignDoesNotUse) {
    ChangeRig rig; rig.build(true, true, true);
    ASSERT_EQ(MobileGS::kRelocOk, rig.g.lastRelocReject());
    ASSERT_TRUE(rig.registered);
    std::fprintf(stderr, "change pass: judged %ld seen %ld\n", rig.judged, rig.seen);
    EXPECT_GT(rig.judged, 0);
    EXPECT_GT(rig.seen, 0) << "a whole patch painted, in the wrong colour, was not seen";
}

TEST(MobileGSTeleological, ThePassSeesNothingOnTheBareWall) {
    ChangeRig rig; rig.build(true, false, true);
    ASSERT_EQ(MobileGS::kRelocOk, rig.g.lastRelocReject());
    EXPECT_GT(rig.judged, 0);
    EXPECT_EQ(0, rig.seen) << "the unpainted wall looked like paint";
}

TEST(MobileGSTeleological, AClutterOfNonsenseFailsRegistrationAndTheStatusSaysSo) {
    MobileGS g;
    g.initialize(0, 0);
    const float K[4] = {700.f, 700.f, 400.f, 300.f};
    g.setCaptureImage(makeWall(800, 600, 0x51de), K);
    EXPECT_FALSE(g.setCleanWallImage(cv::Mat(600, 800, CV_8UC3, cv::Scalar(90, 90, 90))));
    EXPECT_EQ(MobileGS::kCleanFailed, g.cleanWallStatus());
    g.clearCleanWallImage();
    EXPECT_EQ(MobileGS::kCleanNone, g.cleanWallStatus());
    g.destroy();
}

TEST(MobileGSTeleological, ACleanWallPhotoGivenBeforeTheCapturePhotoWaitsForIt) {
    MobileGS g;
    g.initialize(0, 0);
    const float K[4] = {700.f, 700.f, 400.f, 300.f};
    const cv::Mat wall = makeWall(800, 600, 0x51de);
    EXPECT_FALSE(g.setCleanWallImage(wall));
    EXPECT_EQ(MobileGS::kCleanPending, g.cleanWallStatus());
    g.setCaptureImage(wall, K);
    EXPECT_EQ(MobileGS::kCleanRegistered, g.cleanWallStatus());
    g.destroy();
}
