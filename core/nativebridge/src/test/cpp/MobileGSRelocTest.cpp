// Host tests for the relocalization pass and the pure helpers around it. The pass is driven through
// the real public entry point (scheduleRelocCheck in EVAL SYNC mode, which runs it inline on the
// calling thread), against a synthetic textured wall, so these exercise production code rather than
// a restatement of it.
#include <gtest/gtest.h>
#include <opencv2/opencv.hpp>
#include <cmath>
#include <cstring>
#include "MobileGS.h"
#include "KeypointGrid.h"
#include "MobileGSTestPeer.h"

namespace {

// Deterministic, corner-rich "marks on a wall": rectangles, lines and blobs on a mid-grey ground.
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

// A metric doodle of [img] as a frontal wall at Z = 1 m in the capture camera's (OpenCV) frame,
// built by the production generateDoodle path from a constant 1000 mm depth map.
void doodleFrontalWall(MobileGS& g, const cv::Mat& img, const float K[4]) {
    const int dw = img.cols / 4, dh = img.rows / 4;
    std::vector<uint16_t> depth((size_t)dw * dh, 1000);
    const float view[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    auto fd = g.generateDoodle(img, cv::Mat(), reinterpret_cast<const uint8_t*>(depth.data()),
                                    dw, dh, dw * (int)sizeof(uint16_t), K, view);
    ASSERT_GE(g.getWallKeypointCount(), 50) << "synthetic wall too featureless for the test";
}

// doodle_from_design for a design composite that IS the captured image, laid on the Z = 1 wall:
// design-local (lx, ly) = image (u, v) mapped through the capture camera. OverlayRenderer's V flip
// means design +y is image -y, hence the -1 on Y (and Z, to keep it a proper rotation).
void placeDesignOnImage(MobileGS& g, int w, int h, const float K[4], float out[16], float& hw, float& hh) {
    hw = (float)w / (2.0f * K[0]);
    hh = (float)h / (2.0f * K[1]);
    const float m[16] = {1,0,0,0, 0,-1,0,0, 0,0,-1,0,
                         (w * 0.5f - K[2]) / K[0], (h * 0.5f - K[3]) / K[1], 1.0f, 1};
    std::memcpy(out, m, sizeof m);
    g.setDesignPlacement(out, hw, hh);
}

} // namespace

// ---- Eval sync cadence (replaces JVM restatements of this arithmetic in EvalSyncRelocGuardTest) ----

TEST(MobileGSEvalSync, CadenceIsFlooredAtOneAndZeroMeansOff) {
    MobileGS g;
    EXPECT_EQ(0, g.evalSyncEveryN()) << "a fresh engine is not in sync mode";
    for (int n : {0, -1, INT32_MIN}) {
        g.setEvalSyncReloc(true, n);
        EXPECT_EQ(1, g.evalSyncEveryN()) << "N=" << n << " must floor to every frame, never to none";
    }
    g.setEvalSyncReloc(true, 5);
    EXPECT_EQ(5, g.evalSyncEveryN());
    g.setEvalSyncReloc(false, 5);
    EXPECT_EQ(0, g.evalSyncEveryN()) << "0 is the off marker, unreachable as a real cadence";
}

TEST(MobileGSEvalSync, RunsExactlyOnePassEveryNFramesAndResetsPhaseOnToggle) {
    MobileGS g;
    g.initialize(0, 0);
    const cv::Mat wall = makeWall(320, 240);
    const float K[4] = {300.f, 300.f, 160.f, 120.f};
    doodleFrontalWall(g, wall, K);

    // A pass always overwrites the reject code, so a sentinel tells which frames ran one.
    auto countPasses = [&](int frames) {
        int passes = 0;
        for (int i = 0; i < frames; ++i) {
            MobileGSTestPeer::setRelocReject(g, 99);
            g.scheduleRelocCheck(wall);
            if (g.lastRelocReject() != 99) ++passes;
        }
        return passes;
    };
    for (int everyN : {1, 3, 5}) {
        g.setEvalSyncReloc(true, everyN);
        EXPECT_EQ(30 / everyN, countPasses(30)) << "cadence " << everyN;
    }
    // Phase resets on the transition: frame 3 of a fresh every-3 run is the first pass.
    g.setEvalSyncReloc(true, 3);
    EXPECT_EQ(0, MobileGSTestPeer::evalFrameCounter(g));
    EXPECT_EQ(0, countPasses(2));
    EXPECT_EQ(1, countPasses(1));
    g.setEvalSyncReloc(false, 1);
    g.destroy();
}

// ---- Reloc pass behaviour ------------------------------------------------------------------------

TEST(MobileGSReloc, LocksOnTheDoodledWallAndPublishesTheLiveCamera) {
    MobileGS g;
    g.initialize(0, 0);
    const cv::Mat full = makeWall(800, 600);
    const float Kfp[4] = {700.f, 700.f, 400.f, 300.f};
    doodleFrontalWall(g, full, Kfp);

    // The live frame is a CROP of the capture: same focal, shifted principal point. Correct
    // geometry is only recovered if the solve and every consumer use the LIVE camera.
    const cv::Rect crop(100, 80, 640, 480);
    const cv::Mat live = full(crop).clone();
    const float Klive[4] = {700.f, 700.f, 400.f - crop.x, 300.f - crop.y};
    g.setLiveIntrinsics(Klive);
    g.setEvalSyncReloc(true, 1);
    g.scheduleRelocCheck(live);

    ASSERT_EQ(MobileGS::kRelocOk, g.lastRelocReject());
    float out[35];
    g.getRelocResult(out, false);
    // camera_from_doodle: the live camera is the capture camera, so ~identity.
    EXPECT_NEAR(0.f, out[12], 0.02f);
    EXPECT_NEAR(0.f, out[13], 0.02f);
    EXPECT_NEAR(0.f, out[14], 0.02f);
    for (int i = 0; i < 4; ++i) EXPECT_FLOAT_EQ(Klive[i], MobileGSTestPeer::pnpIntrinsics(g)[i]);
    // Inliers can never exceed the correspondences the solve was given, and the published count
    // must BE that set (mPnpMatchCount is recorded at the solve).
    EXPECT_LE(g.lastRelocInliers(), g.lastRelocMatches());
    EXPECT_EQ((int)out[17], g.lastRelocMatches());
    g.setEvalSyncReloc(false, 1);
    g.destroy();
}

// The standalone (SphereSLAM) doodle frame: points on the centred page plane z = 0, Y up, an
// identity doodle anchor — no capture camera. A live camera square-on to the page must lock
// and report camera_from_doodle in that frame: page x → image right, page y (up) → image down,
// so R = diag(1, −1, −1) and the page lies `distance` ahead.
TEST(MobileGSReloc, LocksOnAStandalonePageFrameDoodle) {
    MobileGS g;
    g.initialize(0, 0);
    const cv::Mat page = makeWall(640, 480);
    cv::Mat gray;
    cv::cvtColor(page, gray, cv::COLOR_BGR2GRAY);
    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;
    cv::ORB::create(1500)->detectAndCompute(gray, cv::noArray(), kps, descs);
    ASSERT_GE((int)kps.size(), 100) << "synthetic page too featureless for the test";

    // 1 mm per page pixel, centred on the image centre, Y up.
    const float unitsPerPx = 0.001f, distance = 0.5f;
    const float cx = (page.cols - 1) * 0.5f, cy = (page.rows - 1) * 0.5f;
    std::vector<cv::Point3f> points;
    for (const auto& k : kps) points.emplace_back((k.pt.x - cx) * unitsPerPx, -(k.pt.y - cy) * unitsPerPx, 0.f);
    const float identity[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    // A camera `distance` from the page with f = distance / unitsPerPx sees the page 1:1.
    const float K[4] = {distance / unitsPerPx, distance / unitsPerPx, cx, cy};
    g.restoreWallDoodleMetric(descs, points, identity, K);
    EXPECT_FALSE(MobileGSTestPeer::hasView(g)) << "a page frame has no capture-camera view";

    g.setLiveIntrinsics(K);
    g.setEvalSyncReloc(true, 1);
    g.scheduleRelocCheck(page);

    ASSERT_EQ(MobileGS::kRelocOk, g.lastRelocReject());
    float out[35];
    g.getRelocResult(out, false);
    // Column-major camera_from_doodle.
    EXPECT_NEAR(1.f, out[0], 0.02f);
    EXPECT_NEAR(-1.f, out[5], 0.02f);
    EXPECT_NEAR(-1.f, out[10], 0.02f);
    EXPECT_NEAR(0.f, out[12], 0.005f);
    EXPECT_NEAR(0.f, out[13], 0.005f);
    EXPECT_NEAR(distance, out[14], 0.01f);
    g.setEvalSyncReloc(false, 1);
    g.destroy();
}

TEST(MobileGSReloc, PaintMarkCorrespondencesCountInThePublishedMatchTotal) {
    MobileGS g;
    g.initialize(0, 0);
    const cv::Mat wall = makeWall(640, 480);
    const float K[4] = {600.f, 600.f, 320.f, 240.f};
    doodleFrontalWall(g, wall, K);
    g.setLiveIntrinsics(K);
    g.setEvalSyncReloc(true, 1);
    g.scheduleRelocCheck(wall);
    ASSERT_EQ(MobileGS::kRelocOk, g.lastRelocReject());
    const int wallOnly = g.lastRelocMatches();

    // Paint marks: the same wall features re-registered as painted design features. They are
    // matched AFTER the backbone and appended to the PnP set, so they must appear in the total.
    std::vector<cv::KeyPoint> kps; cv::Mat descs;
    cv::Mat gray; cv::cvtColor(wall, gray, cv::COLOR_RGB2GRAY);
    cv::createCLAHE(2.0, cv::Size(8, 8))->apply(gray, gray);
    cv::ORB::create(1000)->detectAndCompute(gray, cv::noArray(), kps, descs);
    std::vector<cv::Point3f> pts;
    for (const auto& k : kps) pts.emplace_back((k.pt.x - K[2]) / K[0], (k.pt.y - K[3]) / K[1], 1.0f);
    g.restorePaintMarks(descs, pts);
    g.scheduleRelocCheck(wall);
    ASSERT_EQ(MobileGS::kRelocOk, g.lastRelocReject());

    float out[35];
    g.getRelocResult(out, false);
    EXPECT_GT(g.lastRelocMatches(), wallOnly) << "paint-mark correspondences missing from the total";
    EXPECT_EQ((int)out[17], g.lastRelocMatches()) << "published total is not the set PnP solved";
    EXPECT_LE(g.lastRelocInliers(), g.lastRelocMatches()) << "inlier ratio above 100%";
    g.setEvalSyncReloc(false, 1);
    g.destroy();
}

TEST(MobileGSReloc, GatedCorroborationProjectsWithTheLiveCamera) {
    MobileGS g;
    g.initialize(0, 0);
    const cv::Mat full = makeWall(800, 600, 0x51de);
    const float Kfp[4] = {700.f, 700.f, 400.f, 300.f};
    doodleFrontalWall(g, full, Kfp);
    // The design composite is the captured image itself, placed exactly where it was captured.
    g.setArtworkDoodle(full, nullptr, 0, 0, 0, nullptr, nullptr);
    float fpFromDesign[16]; float hw, hh;
    placeDesignOnImage(g, full.cols, full.rows, Kfp, fpFromDesign, hw, hh);

    const cv::Rect crop(120, 90, 640, 480);
    const cv::Mat live = full(crop).clone();
    const float Klive[4] = {700.f, 700.f, 400.f - crop.x, 300.f - crop.y};
    g.setLiveIntrinsics(Klive);
    g.setEvalSyncReloc(true, 1);
    g.scheduleRelocCheck(live);

    ASSERT_EQ(MobileGS::kRelocOk, g.lastRelocReject());
    ASSERT_EQ(MobileGS::kCorrobGated, g.corrobGateReason());
    ASSERT_GT(g.corrobPredicted(), 50);
    // Projected with the capture camera instead, every prediction lands (120, 90) px away -- far
    // outside a ~20 px search radius -- and almost nothing corroborates.
    // The design here IS the captured wall, so the wall exclusion list rightly discards the features the
    // bare wall explains as well as the design does (most of them); what is left is still far above the
    // ~0 of a mis-projected search.
    EXPECT_GT(g.corrobMatched(), g.corrobPredicted() / 8)
        << "matched " << g.corrobMatched() << " of " << g.corrobPredicted();
    g.setEvalSyncReloc(false, 1);
    g.destroy();
}

TEST(MobileGSReloc, EarlyOutAttemptsDoNotKeepThePreviousOutcomeCodes) {
    MobileGS g;
    g.initialize(0, 0);
    const cv::Mat wall = makeWall(320, 240);
    const float K[4] = {300.f, 300.f, 160.f, 120.f};
    doodleFrontalWall(g, wall, K);
    g.setEvalSyncReloc(true, 1);

    // No artwork registered: the pass reaches tryUpdateDoodle, which returns before the
    // promotion block -- and must not leave the previous attempt's grow verdict standing.
    MobileGSTestPeer::setCorrobGate(g, MobileGS::kCorrobGated);
    MobileGSTestPeer::setGrowOutcome(g, MobileGS::kGrowPromoted);
    g.scheduleRelocCheck(wall);
    EXPECT_EQ(MobileGS::kCorrobNoArtwork, g.corrobGateReason());
    EXPECT_EQ(MobileGS::kGrowNotRun, g.growOutcome());

    // Doodle replaced by one with no 3D points: the pass returns before tryUpdateDoodle.
    MobileGSTestPeer::setCorrobGate(g, MobileGS::kCorrobGated);
    MobileGSTestPeer::setGrowOutcome(g, MobileGS::kGrowPromoted);
    g.restoreWallDoodle(cv::Mat(3, 32, CV_8U, cv::Scalar(1)), {});
    g.scheduleRelocCheck(wall);
    EXPECT_EQ(MobileGS::kRelocNoDoodle, g.lastRelocReject());
    EXPECT_EQ(MobileGS::kCorrobNotRun, g.corrobGateReason());
    EXPECT_EQ(MobileGS::kGrowNotRun, g.growOutcome());
    g.setEvalSyncReloc(false, 1);
    g.destroy();
}

TEST(MobileGSReloc, ASolveInFlightWhenTheDoodleIsReplacedNeverPublishes) {
    MobileGS g;
    g.initialize(0, 0);
    const cv::Mat wall = makeWall(640, 480);
    const float K[4] = {600.f, 600.f, 320.f, 240.f};
    doodleFrontalWall(g, wall, K);
    g.setEvalSyncReloc(true, 1);

    // Control: with nothing replaced the same frame locks, so the assertions below are about the
    // generation check and not about a frame that never solves.
    g.scheduleRelocCheck(wall);
    ASSERT_EQ(MobileGS::kRelocOk, g.lastRelocReject());
    const long seqBefore = MobileGSTestPeer::pnpSeq(g);

    // Replace the doodle (a re-capture's restore) at the instant the solve is about to publish.
    MobileGSTestPeer::setBeforeRelocPublishHook(g, [&g] {
        g.restoreWallDoodle(cv::Mat(3, 32, CV_8U, cv::Scalar(5)), {{0,0,1}, {1,0,1}, {0,1,1}});
    });
    g.scheduleRelocCheck(wall);
    MobileGSTestPeer::setBeforeRelocPublishHook(g, nullptr);

    EXPECT_EQ(seqBefore, MobileGSTestPeer::pnpSeq(g)) << "a solve against the replaced doodle advanced the seq";
    float out[35];
    g.getRelocResult(out, true);
    EXPECT_FLOAT_EQ(0.f, out[16]) << "the old wall's inliers were latched onto the new doodle";
    EXPECT_FLOAT_EQ(0.f, out[17]);
    for (int i = 0; i < 16; ++i) EXPECT_FLOAT_EQ(i % 5 == 0 ? 1.f : 0.f, out[i]) << "pose[" << i << "]";
    EXPECT_NE(MobileGS::kRelocOk, g.lastRelocReject());
    g.setEvalSyncReloc(false, 1);
    g.destroy();
}

TEST(MobileGSReloc, ASolveInFlightWhenTheDoodleIsClearedNeverPublishes) {
    MobileGS g;
    g.initialize(0, 0);
    const cv::Mat wall = makeWall(640, 480);
    const float K[4] = {600.f, 600.f, 320.f, 240.f};
    doodleFrontalWall(g, wall, K);
    g.setEvalSyncReloc(true, 1);
    const long seqBefore = MobileGSTestPeer::pnpSeq(g);
    MobileGSTestPeer::setBeforeRelocPublishHook(g, [&g] { g.clearWallDoodle(); });
    g.scheduleRelocCheck(wall);
    MobileGSTestPeer::setBeforeRelocPublishHook(g, nullptr);
    EXPECT_EQ(seqBefore, MobileGSTestPeer::pnpSeq(g));
    EXPECT_NE(MobileGS::kRelocOk, g.lastRelocReject());
    g.setEvalSyncReloc(false, 1);
    g.destroy();
}

// ---- Pure helpers --------------------------------------------------------------------------------

TEST(MobileGSIntrinsics, LiveWinsThenDoodleThenAFrameCentredDefault) {
    const float live[4] = {500, 510, 300, 200}, fp[4] = {900, 910, 960, 540}, none[4] = {0, 0, 0, 0};
    double k[4];
    EXPECT_TRUE(MobileGS::relocCameraIntrinsics(live, fp, 640, 480, k));
    EXPECT_DOUBLE_EQ(500, k[0]); EXPECT_DOUBLE_EQ(200, k[3]);
    EXPECT_TRUE(MobileGS::relocCameraIntrinsics(none, fp, 640, 480, k));
    EXPECT_DOUBLE_EQ(900, k[0]); EXPECT_DOUBLE_EQ(540, k[3]);
    EXPECT_FALSE(MobileGS::relocCameraIntrinsics(none, none, 640, 480, k));
    EXPECT_DOUBLE_EQ(320, k[2]) << "default principal point must be the frame's centre";
    EXPECT_DOUBLE_EQ(240, k[3]);
}

TEST(MobileGSIntrinsics, RectifyHomographyUsesBothCameras) {
    // Same pose, different cameras (the live frame is the capture at half resolution): a doodle
    // pixel must map to the live pixel of the same ray, i.e. H = K_cur * K_fp^-1.
    const cv::Matx33d Kfp(1000, 0, 960, 0, 1000, 540, 0, 0, 1);
    const cv::Matx33d Kcur(500, 0, 480, 0, 500, 270, 0, 0, 1);
    bool ok = false;
    const cv::Matx33d H = MobileGS::planeHomography(Kcur, Kfp, cv::Matx33d::eye(), cv::Vec3d(0, 0, 0),
                                                    cv::Vec3d(0, 0, 1), 2.0, &ok);
    ASSERT_TRUE(ok);
    const cv::Vec3d p = H * cv::Vec3d(1460, 790, 1);   // 500 px right / 250 px down of centre
    EXPECT_NEAR(730.0, p[0] / p[2], 1e-6);
    EXPECT_NEAR(395.0, p[1] / p[2], 1e-6);

    // A pure translation along x at distance d shifts every pixel by fx_cur * tx / d.
    const cv::Matx33d H2 = MobileGS::planeHomography(Kcur, Kfp, cv::Matx33d::eye(), cv::Vec3d(0.1, 0, 0),
                                                     cv::Vec3d(0, 0, 1), 2.0, &ok);
    ASSERT_TRUE(ok);
    const cv::Vec3d q = H2 * cv::Vec3d(960, 540, 1);
    EXPECT_NEAR(480.0 + 500 * 0.1 / 2.0, q[0] / q[2], 1e-6);
}

TEST(MobileGSFrames, PageFrameIsNotACaptureCamera) {
    std::vector<cv::Point3f> page, camera;
    cv::RNG rng(3);
    for (int i = 0; i < 40; ++i) {
        const float x = rng.uniform(-0.5f, 0.5f), y = rng.uniform(-0.4f, 0.4f);
        page.emplace_back(x, y, 0.0f);                 // centred SphereSLAM page frame
        camera.emplace_back(x, y, 2.0f + 0.001f * x);  // capture camera, wall 2 m ahead
    }
    EXPECT_FALSE(MobileGS::doodleFrameIsCaptureCamera(page));
    EXPECT_TRUE(MobileGS::doodleFrameIsCaptureCamera(camera));
    // Scale-free: normalised (non-metric) page units read the same.
    for (auto& p : page) p *= 0.01f;
    EXPECT_FALSE(MobileGS::doodleFrameIsCaptureCamera(page));
}

TEST(MobileGSFrames, AutoFitRefusesAPageFrameDoodleWithAReason) {
    MobileGS g;
    std::vector<cv::Point3f> page;
    for (int i = 0; i < 20; ++i) page.emplace_back(-0.5f + 0.05f * i, (i % 5) * 0.1f - 0.2f, 0.0f);
    const float anchor[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    const float K[4] = {500.f, 500.f, 160.f, 120.f};
    g.restoreWallDoodleMetric(cv::Mat(20, 32, CV_8U, cv::Scalar(4)), page, anchor, K);
    const cv::Mat wall = makeWall(320, 240);
    g.setArtworkDoodle(wall, nullptr, 0, 0, 0, nullptr, nullptr);
    g.setCaptureImage(wall, K);
    const float fpFromDesign[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    g.setDesignPlacement(fpFromDesign, 0.5f, 0.4f);
    float out5[5];
    EXPECT_FALSE(g.autoFitDesign(out5));
    EXPECT_EQ(MobileGS::kAutoFitPageFrame, g.lastAutoFitResult());
}

TEST(MobileGSRelocScales, SuperPointPassesAreRelativeToWhatThePlainPassSaw) {
    // 640x480 is exactly at the network cap: plain pass sees 1.0, so 0.5x full frame and a 2x
    // magnified centred half-size window (which fits the cap after magnification).
    auto p = MobileGS::relocScalePasses(640, 480, true);
    ASSERT_EQ(2u, p.size());
    EXPECT_EQ(cv::Rect(0, 0, 640, 480), p[0].roi); EXPECT_DOUBLE_EQ(0.5, p[0].scale);
    EXPECT_EQ(cv::Rect(160, 120, 320, 240), p[1].roi); EXPECT_DOUBLE_EQ(2.0, p[1].scale);
    // 1280x960: the plain pass is downscaled to 0.5 inside SuperPoint, so the passes are 0.25 and
    // 1.0 (native pixels of the centre) -- not 0.5 and 2.0, which both collapse onto the plain one.
    p = MobileGS::relocScalePasses(1280, 960, true);
    EXPECT_DOUBLE_EQ(0.25, p[0].scale);
    EXPECT_EQ(cv::Rect(320, 240, 640, 480), p[1].roi); EXPECT_DOUBLE_EQ(1.0, p[1].scale);
    // Small frame: the whole 2x frame fits the cap, so no crop.
    p = MobileGS::relocScalePasses(320, 240, true);
    EXPECT_EQ(cv::Rect(0, 0, 320, 240), p[1].roi); EXPECT_DOUBLE_EQ(2.0, p[1].scale);
    // ORB has no input cap: unchanged full-frame 0.5x / 2x.
    p = MobileGS::relocScalePasses(640, 480, false);
    EXPECT_EQ(cv::Rect(0, 0, 640, 480), p[1].roi); EXPECT_DOUBLE_EQ(2.0, p[1].scale);
    // Every magnified pass fits the network's input after scaling.
    for (auto wh : {cv::Size(640, 480), cv::Size(480, 640), cv::Size(1920, 1080), cv::Size(300, 200)}) {
        for (const auto& s : MobileGS::relocScalePasses(wh.width, wh.height, true)) {
            EXPECT_LE(std::max(s.roi.width, s.roi.height) * s.scale, 640.0 + 1.0);
            EXPECT_TRUE((cv::Rect(0, 0, wh.width, wh.height) & s.roi) == s.roi);
        }
    }
}

TEST(KeypointGridExact, SupersetIsCutToTheDisc) {
    std::vector<cv::KeyPoint> kps = {
        cv::KeyPoint(100.f, 100.f, 1.f),          // at the query
        cv::KeyPoint(109.f, 100.f, 1.f),          // inside r = 10
        cv::KeyPoint(108.f, 108.f, 1.f),          // diagonal, ~11.3 px: same/neighbouring cell, outside
        cv::KeyPoint(119.f, 119.f, 1.f),          // swept corner cell, ~26.9 px
    };
    KeypointGrid grid;
    grid.build(kps, 10.f);
    std::vector<int> cand;
    grid.candidatesWithin(100.f, 100.f, 10.f, cand);
    ASSERT_EQ(4u, cand.size()) << "the grid's superset should include the corner cells";
    KeypointGrid::keepWithinRadius(kps, 100.f, 100.f, 10.f, cand);
    std::sort(cand.begin(), cand.end());
    EXPECT_EQ((std::vector<int>{0, 1}), cand);
    // Inclusive boundary, and invalid indices never read.
    std::vector<int> edge = {1, 7, -1};
    KeypointGrid::keepWithinRadius(kps, 100.f, 100.f, 9.f, edge);
    EXPECT_EQ((std::vector<int>{1}), edge);
}

// ---- The sensor turn -----------------------------------------------------------------------------

namespace {

// earth (East-North-Up) <- OpenCV camera looking north, level and upright: image right = east,
// image down = earth down, forward = north.
cv::Matx33d lookingNorth() { return cv::Matx33d(1, 0, 0, 0, 0, 1, 0, -1, 0); }

// capture-camera <- live-camera for a camera pitched up by [deg] (about its own x axis), then rolled
// by [rollDeg] about its optical axis.
cv::Matx33d pitchedUp(double deg, double rollDeg = 0.0) {
    const double t = deg * CV_PI / 180.0, r = rollDeg * CV_PI / 180.0;
    const cv::Matx33d pitch(1, 0, 0, 0, std::cos(t), -std::sin(t), 0, std::sin(t), std::cos(t));
    const cv::Matx33d roll(std::cos(r), -std::sin(r), 0, std::sin(r), std::cos(r), 0, 0, 0, 1);
    return pitch * roll;
}

cv::Point2d apply(const cv::Matx33d& H, double x, double y) {
    const cv::Vec3d p = H * cv::Vec3d(x, y, 1);
    return {p[0] / p[2], p[1] / p[2]};
}

} // namespace

TEST(MobileGSSensorTurn, TurnsALookUpWithRollSquareOnAndUpright) {
    // A north-facing camera pitched 35 deg up and rolled 20 deg, looking at a wall that faces south.
    const double K[4] = {500, 500, 320, 240};
    const cv::Matx33d capFromLive = pitchedUp(35.0, 20.0);
    const cv::Matx33d earthFromLive = lookingNorth() * capFromLive;
    cv::Matx33d H; double turn = 0, obliq = 0;
    ASSERT_TRUE(MobileGS::sensorTurnHomography(earthFromLive, cv::Vec3d(0, -1, 0), K, H, turn, obliq));
    EXPECT_NEAR(35.0, obliq, 1e-6);
    // A square on the wall, seen by the live camera, comes out square and upright.
    const cv::Matx33d Kc(K[0], 0, K[2], 0, K[1], K[3], 0, 0, 1);
    auto seen = [&](double ex, double up) { // a wall point 2 m north, ex east and up metres
        const cv::Vec3d earth(ex, 2.0, up);
        const cv::Vec3d cam = earthFromLive.t() * earth;
        const cv::Vec3d px = Kc * cam;
        return apply(H, px[0] / px[2], px[1] / px[2]);
    };
    const cv::Point2d tl = seen(-0.5, 1.9), tr = seen(0.5, 1.9), bl = seen(-0.5, 0.9), br = seen(0.5, 0.9);
    EXPECT_NEAR(tl.y, tr.y, 1e-6) << "top edge level";
    EXPECT_NEAR(bl.y, br.y, 1e-6) << "bottom edge level";
    EXPECT_NEAR(tl.x, bl.x, 1e-6) << "left edge vertical";
    EXPECT_NEAR(tr.x, br.x, 1e-6) << "right edge vertical";
    EXPECT_NEAR(tr.x - tl.x, bl.y - tl.y, 1e-6) << "square stays square";
    EXPECT_LT(tl.y, bl.y) << "up is up";
    // The live frame's centre lands on the turned frame's centre.
    const cv::Point2d c = apply(H, K[2], K[3]);
    EXPECT_NEAR(K[2], c.x, 1e-6);
    EXPECT_NEAR(K[3], c.y, 1e-6);
}

TEST(MobileGSSensorTurn, NothingToTurnOrNothingToSee) {
    const double K[4] = {500, 500, 320, 240};
    cv::Matx33d H; double turn = 0, obliq = 0;
    // Already square-on and upright.
    EXPECT_FALSE(MobileGS::sensorTurnHomography(lookingNorth(), cv::Vec3d(0, -1, 0), K, H, turn, obliq));
    // Looking away from the wall.
    EXPECT_FALSE(MobileGS::sensorTurnHomography(lookingNorth(), cv::Vec3d(0, 1, 0), K, H, turn, obliq));
    // Edge-on: 80 deg off the normal.
    EXPECT_FALSE(MobileGS::sensorTurnHomography(lookingNorth() * pitchedUp(80.0), cv::Vec3d(0, -1, 0), K, H, turn, obliq));
    // No normal.
    EXPECT_FALSE(MobileGS::sensorTurnHomography(lookingNorth() * pitchedUp(30.0), cv::Vec3d(0, 0, 0), K, H, turn, obliq));
}

TEST(MobileGSSensorTurn, LearnsTheWallNormalInTheEarthFromALock) {
    // Doodle points on the plane z = 1 m of the capture camera; the live camera IS the capture
    // camera (identity pose), looking north: the wall faces south, toward it.
    std::vector<cv::Point3f> pts;
    for (int i = 0; i < 40; ++i) pts.emplace_back((float)(i % 8) * 0.1f - 0.35f, (float)(i / 8) * 0.1f - 0.2f, 1.0f);
    cv::Vec3d n;
    ASSERT_TRUE(MobileGS::wallNormalFromLock(pts, cv::Matx33d::eye(), cv::Vec3d(0, 0, 0), lookingNorth(), n));
    EXPECT_NEAR(0.0, n[0], 1e-9);
    EXPECT_NEAR(-1.0, n[1], 1e-9);
    EXPECT_NEAR(0.0, n[2], 1e-9);
    // Pitched up 30 deg, the pose and the attitude turn together: the same wall, the same normal.
    const cv::Matx33d capFromLive = pitchedUp(30.0);
    ASSERT_TRUE(MobileGS::wallNormalFromLock(pts, capFromLive.t(), cv::Vec3d(0, 0, 0), lookingNorth() * capFromLive, n));
    EXPECT_NEAR(-1.0, n[1], 1e-9);
    // A line is not a wall.
    std::vector<cv::Point3f> line;
    for (int i = 0; i < 20; ++i) line.emplace_back((float)i * 0.1f, 0.f, 1.f);
    EXPECT_FALSE(MobileGS::wallNormalFromLock(line, cv::Matx33d::eye(), cv::Vec3d(0, 0, 0), lookingNorth(), n));
}

TEST(MobileGSSensorTurn, ALostLookUpRelocksThroughTheTurnAndTheLockTeachesTheNormal) {
    MobileGS g;
    g.initialize(0, 0);
    // A wide lens (about +-40 deg vertically), so a steep look up still keeps the wall in view.
    const cv::Mat full = makeWall(800, 600);
    const float Kf[4] = {350.f, 350.f, 400.f, 300.f};
    doodleFrontalWall(g, full, Kf);
    g.setLiveIntrinsics(Kf);
    g.setEvalSyncReloc(true, 1);
    g.setTrackingPoseValid(false); // lost: no pose to rectify against

    // The capture view itself: locks, and (with the attitude) teaches the wall's normal.
    const cv::Matx33d north = lookingNorth();
    float att[9];
    for (int i = 0; i < 9; ++i) att[i] = (float)north.val[i];
    g.setCameraAttitude(att);
    g.scheduleRelocCheck(full);
    ASSERT_EQ(MobileGS::kRelocOk, g.lastRelocReject());
    float n[3];
    ASSERT_TRUE(g.getWallNormalEnu(n));
    EXPECT_NEAR(-1.f, n[1], 0.02f);
    EXPECT_EQ(-1, g.lastRelocTurnDeg()) << "square-on: nothing to turn";

    // Now look up 35 deg and roll 25 deg from the same spot: the frame is the capture turned.
    const cv::Matx33d capFromLive = pitchedUp(35.0, 25.0);
    const cv::Matx33d K(Kf[0], 0, Kf[2], 0, Kf[1], Kf[3], 0, 0, 1);
    const cv::Matx33d liveFromCapPx = K * capFromLive.t() * K.inv();
    cv::Mat live;
    cv::warpPerspective(full, live, cv::Mat(liveFromCapPx), full.size(), cv::INTER_LINEAR,
                        cv::BORDER_CONSTANT, cv::Scalar(150, 150, 150));
    const cv::Matx33d earthFromLive = north * capFromLive;
    for (int i = 0; i < 9; ++i) att[i] = (float)earthFromLive.val[i];
    g.setCameraAttitude(att);
    g.scheduleRelocCheck(live);
    EXPECT_GE(g.lastRelocTurnDeg(), 35);
    EXPECT_GT(g.lastRelocTurnCorr(), 0) << "the turned frame matched";
    ASSERT_EQ(MobileGS::kRelocOk, g.lastRelocReject());
    // camera_from_doodle: the live camera at the capture's centre, turned.
    float out[35];
    g.getRelocResult(out, false);
    EXPECT_NEAR(0.f, out[12], 0.03f);
    EXPECT_NEAR(0.f, out[13], 0.03f);
    EXPECT_NEAR(0.f, out[14], 0.03f);

    // Forgetting the attitude turns nothing.
    g.setCameraAttitude(nullptr);
    g.scheduleRelocCheck(live);
    EXPECT_EQ(-1, g.lastRelocTurnDeg());
    // A cleared doodle forgets the wall's normal.
    g.clearWallDoodle();
    EXPECT_FALSE(g.getWallNormalEnu(n));
    g.setEvalSyncReloc(false, 1);
    g.destroy();
}
