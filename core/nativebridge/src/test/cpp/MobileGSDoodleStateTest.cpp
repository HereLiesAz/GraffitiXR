// BACKLOG Phase 7: stale co-registration state must not survive a doodle replacement.
// Phase 1 fixed restoreWallDoodle (descriptors-only) and alignToDoodle (co-op peer) to
// reset the capture view / anchor / canonical patch a previous metric doodle left behind;
// these pin those resets. Private state is read through the MobileGSTestPeer friend.
#include <gtest/gtest.h>
#include <opencv2/opencv.hpp>
#include <cstring>
#include <jni.h>
#include "MobileGS.h"
#include "MobileGSTestPeer.h"

JavaVM* gJvm = nullptr; // defined in GraffitiJNI.cpp on device; never attached in host tests

namespace {

const float kIdentity[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
const float kAnchor[16]   = {0,1,0,0, -1,0,0,0, 0,0,1,0, 0.5f,-0.25f,-2.0f,1};
const float kView[16]     = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,-1.5f,1};
const float kIntr[4]      = {500.f, 500.f, 320.f, 240.f};

void seedMetric(MobileGS& g) {
    cv::Mat descs(4, 32, CV_8U, cv::Scalar(7));
    std::vector<cv::Point3f> pts = {{0,0,0}, {0.1f,0,0}, {0,0.1f,0}, {0.1f,0.1f,0}};
    g.restoreWallDoodleMetric(descs, pts, kAnchor, kIntr, kView);
    g.setWallPatch(cv::Mat(64, 64, CV_8UC1, cv::Scalar(200)));
    ASSERT_TRUE(MobileGSTestPeer::hasView(g));
    ASSERT_TRUE(MobileGSTestPeer::hasPatch(g));
    ASSERT_EQ(0, std::memcmp(MobileGSTestPeer::anchor(g), kAnchor, sizeof kAnchor));
}

void expectIdentityAnchor(MobileGS& g) {
    float out[16];
    g.getDoodleAnchor(out); // the value Kotlin composes against
    for (int i = 0; i < 16; ++i) EXPECT_FLOAT_EQ(out[i], kIdentity[i]) << "anchor[" << i << "]";
}

void expectZeroIntrinsics(MobileGS& g) {
    for (int i = 0; i < 4; ++i) EXPECT_FLOAT_EQ(MobileGSTestPeer::intrinsics(g)[i], 0.f);
}

} // namespace

TEST(MobileGSDoodleState, DescriptorsOnlyRestoreClearsPreviousCoRegistration) {
    MobileGS g;
    seedMetric(g);
    cv::Mat descs(2, 32, CV_8U, cv::Scalar(9));
    g.restoreWallDoodle(descs, {{1,1,1}, {2,2,2}});
    EXPECT_FALSE(MobileGSTestPeer::hasView(g));
    EXPECT_FALSE(MobileGSTestPeer::hasPatch(g));
    expectIdentityAnchor(g);
    expectZeroIntrinsics(g);
}

TEST(MobileGSDoodleState, PeerDoodleDoesNotInheritLocalAnchorOrView) {
    MobileGS host;
    {
        cv::Mat descs(3, 32, CV_8U, cv::Scalar(3));
        host.restoreWallDoodle(descs, {{0,0,0}, {1,0,0}, {0,1,0}});
    }
    const std::vector<uint8_t> wire = host.exportDoodle();
    ASSERT_FALSE(wire.empty());

    MobileGS guest;
    seedMetric(guest);
    // State co-registered to the LOCAL frame that a peer's doodle must not inherit.
    {
        const float fpFromDesign[16] = {1,0,0,0, 0,-1,0,0, 0,0,-1,0, 0,0,1.5f,1};
        guest.setDesignPlacement(fpFromDesign, 0.4f, 0.3f);
        ASSERT_TRUE(MobileGSTestPeer::hasDesignPlacement(guest));
        cv::Mat mapDescs(10, 32, CV_8U, cv::Scalar(5));
        std::vector<cv::Point3f> mapPts(10, cv::Point3f(0.1f, 0.2f, 1.5f));
        guest.restoreWallFeatureMap(mapDescs, mapPts, {}, {}, kAnchor, kIntr);
        ASSERT_EQ(10, guest.getMapPointCount());
        const float capIntr[4] = {500.f, 500.f, 32.f, 24.f};
        guest.setCaptureImage(cv::Mat(48, 64, CV_8UC3, cv::Scalar(10, 20, 30)), capIntr);
        ASSERT_TRUE(MobileGSTestPeer::hasCapturePhoto(guest));
    }
    const uint64_t mapRevBefore = guest.getWallFeatureMapRevision();

    guest.alignToDoodle(wire.data(), wire.size());
    EXPECT_FALSE(MobileGSTestPeer::hasView(guest));
    expectIdentityAnchor(guest);
    expectZeroIntrinsics(guest);
    // ...and everything else clearWallDoodle drops for the same reason.
    EXPECT_FALSE(MobileGSTestPeer::hasPatch(guest)) << "local canonical patch survived a peer doodle";
    EXPECT_FALSE(MobileGSTestPeer::hasDesignPlacement(guest)) << "design placement is local-frame-relative";
    EXPECT_FLOAT_EQ(0.f, MobileGSTestPeer::designHalfW(guest));
    EXPECT_EQ(0, guest.getMapPointCount()) << "wall feature map is co-registered to the local anchor";
    EXPECT_GT(guest.getWallFeatureMapRevision(), mapRevBefore) << "map clear must be visible to the saver";
    EXPECT_FALSE(MobileGSTestPeer::hasCapturePhoto(guest)) << "capture photo shot the local frame";
    EXPECT_EQ(3, guest.getWallKeypointCount());
}

TEST(MobileGSDoodleState, MetricRestoreOfADifferentDoodleDropsThePatch) {
    MobileGS g;
    seedMetric(g);
    cv::Mat descs(4, 32, CV_8U, cv::Scalar(8));   // different content
    std::vector<cv::Point3f> pts = {{0,0,1}, {0.2f,0,1}, {0,0.2f,1}, {0.2f,0.2f,1}};
    g.restoreWallDoodleMetric(descs, pts, kAnchor, kIntr, kView);
    EXPECT_FALSE(MobileGSTestPeer::hasPatch(g)) << "previous wall's patch survived onto a new wall";
    EXPECT_TRUE(MobileGSTestPeer::hasView(g));
}

TEST(MobileGSDoodleState, MetricRePushOfTheSameDoodleKeepsThePatch) {
    // The Phase-2 repartition path and a standalone re-seed push the identical point set again
    // (only the partition differs). That is the same wall, so its patch must survive.
    MobileGS g;
    seedMetric(g);
    cv::Mat descs(4, 32, CV_8U, cv::Scalar(7));
    std::vector<cv::Point3f> pts = {{0,0,0}, {0.1f,0,0}, {0,0.1f,0}, {0.1f,0.1f,0}};
    g.restoreWallDoodleMetric(descs, pts, kAnchor, kIntr, kView, {0, 1, 2, 0});
    EXPECT_TRUE(MobileGSTestPeer::hasPatch(g));
}

TEST(MobileGSDoodleState, ClearResetsTheOutcomeCodes) {
    MobileGS g;
    seedMetric(g);
    MobileGSTestPeer::setCorrobGate(g, MobileGS::kCorrobGated);
    MobileGSTestPeer::setGrowOutcome(g, MobileGS::kGrowPromoted);
    g.clearWallDoodle();
    EXPECT_EQ(MobileGS::kCorrobNotRun, g.corrobGateReason());
    EXPECT_EQ(MobileGS::kGrowNotRun, g.growOutcome());
    EXPECT_FALSE(MobileGSTestPeer::hasPatch(g));
}

TEST(MobileGSDoodleState, MalformedPeerBytesChangeNothing) {
    MobileGS guest;
    seedMetric(guest);
    const uint8_t junk[8] = {0xff, 0xff, 0xff, 0x7f, 0, 0, 0, 0};
    guest.alignToDoodle(junk, sizeof junk);
    EXPECT_TRUE(MobileGSTestPeer::hasView(guest));
    EXPECT_EQ(0, std::memcmp(MobileGSTestPeer::anchor(guest), kAnchor, sizeof kAnchor));
}

TEST(MobileGSDoodleState, ClearForgetsTheLatchedPnpResultButKeepsTheSeqMonotonic) {
    // A re-capture hands Kotlin the latched result; it was solved against the OLD doodle.
    MobileGS g;
    seedMetric(g);
    MobileGSTestPeer::setPnpResult(g, 40, 50, 7);
    float out[35];
    g.getRelocResult(out, true);
    ASSERT_FLOAT_EQ(40.f, out[16]);
    ASSERT_FLOAT_EQ(7.f, out[18]);

    g.clearWallDoodle();
    g.getRelocResult(out, true);
    EXPECT_FLOAT_EQ(0.f, out[16]) << "stale inliers survived the clear";
    EXPECT_FLOAT_EQ(0.f, out[17]) << "stale matches survived the clear";
    EXPECT_FLOAT_EQ(7.f, out[18]) << "seq must stay monotonic, not rewind to a value a consumer may hold";
    for (int i = 0; i < 16; ++i) EXPECT_FLOAT_EQ(kIdentity[i], out[i]) << "pose[" << i << "]";
}

TEST(MobileGSDoodleState, EveryReplacementAdvancesTheGenerationAndForgetsTheLatchedResult) {
    MobileGS g;
    seedMetric(g);
    const auto g0 = MobileGSTestPeer::doodleGeneration(g);

    // A re-push of the identical frame (repartition) keeps the standing result and the generation.
    MobileGSTestPeer::setPnpResult(g, 40, 50, 7);
    cv::Mat same(4, 32, CV_8U, cv::Scalar(7));
    std::vector<cv::Point3f> samePts = {{0,0,0}, {0.1f,0,0}, {0,0.1f,0}, {0.1f,0.1f,0}};
    g.restoreWallDoodleMetric(same, samePts, kAnchor, kIntr, kView, {0, 1, 2, 0});
    EXPECT_EQ(g0, MobileGSTestPeer::doodleGeneration(g));
    float out[35];
    g.getRelocResult(out, true);
    EXPECT_FLOAT_EQ(40.f, out[16]);

    // A different doodle (re-capture / project switch) advances it and drops the result, while
    // the seq stays monotonic.
    cv::Mat other(4, 32, CV_8U, cv::Scalar(8));
    g.restoreWallDoodleMetric(other, {{0,0,1}, {0.2f,0,1}, {0,0.2f,1}, {0.2f,0.2f,1}}, kAnchor, kIntr, kView);
    const auto g1 = MobileGSTestPeer::doodleGeneration(g);
    EXPECT_GT(g1, g0);
    g.getRelocResult(out, true);
    EXPECT_FLOAT_EQ(0.f, out[16]);
    EXPECT_FLOAT_EQ(7.f, out[18]);

    // Same points but a different anchor is a different frame.
    g.restoreWallDoodleMetric(other, {{0,0,1}, {0.2f,0,1}, {0,0.2f,1}, {0.2f,0.2f,1}}, kIdentity, kIntr, kView);
    EXPECT_GT(MobileGSTestPeer::doodleGeneration(g), g1);

    const auto g2 = MobileGSTestPeer::doodleGeneration(g);
    g.restoreWallDoodle(cv::Mat(3, 32, CV_8U, cv::Scalar(1)), {{0,0,0}, {1,0,0}, {0,1,0}});
    const auto g3 = MobileGSTestPeer::doodleGeneration(g);
    EXPECT_GT(g3, g2);
    g.clearWallDoodle();
    EXPECT_GT(MobileGSTestPeer::doodleGeneration(g), g3);
}

TEST(MobileGSDoodleState, PeerDoodleWithMismatchedPointAndDescriptorCountsIsRejected) {
    MobileGS host;
    cv::Mat descs(3, 32, CV_8U, cv::Scalar(3));
    host.restoreWallDoodle(descs, {{0,0,0}, {1,0,0}, {0,1,0}});
    std::vector<uint8_t> wire = host.exportDoodle();
    ASSERT_FALSE(wire.empty());
    // Wire layout: u32 numPoints, numPoints * Point3f, then u32 descRows... Lower numPoints to 2 and
    // drop one point's bytes so every length check still passes and ONLY the count mismatch remains.
    const uint32_t two = 2;
    std::memcpy(wire.data(), &two, sizeof two);
    wire.erase(wire.begin() + 4 + 2 * sizeof(cv::Point3f), wire.begin() + 4 + 3 * sizeof(cv::Point3f));

    MobileGS guest;
    seedMetric(guest);
    guest.alignToDoodle(wire.data(), wire.size());
    EXPECT_EQ(4, guest.getWallKeypointCount()) << "a mismatched peer doodle replaced the local one";
    EXPECT_TRUE(MobileGSTestPeer::hasView(guest));
}
