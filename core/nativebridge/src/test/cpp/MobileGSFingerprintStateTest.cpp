// BACKLOG Phase 7: stale co-registration state must not survive a fingerprint replacement.
// Phase 1 fixed restoreWallFingerprint (descriptors-only) and alignToFingerprint (co-op peer) to
// reset the capture view / anchor / canonical patch a previous metric fingerprint left behind;
// these pin those resets. Private state is read through the MobileGSTestPeer friend.
#include <gtest/gtest.h>
#include <opencv2/opencv.hpp>
#include <cstring>
#include <jni.h>
#include "MobileGS.h"

JavaVM* gJvm = nullptr; // defined in GraffitiJNI.cpp on device; never attached in host tests

struct MobileGSTestPeer {
    static bool hasView(MobileGS& g) { return g.mHasFingerprintView; }
    static bool hasPatch(MobileGS& g) { return !g.mWallPatch.empty(); }
    static const float* anchor(MobileGS& g) { return g.mFingerprintAnchorMatrix; }
    static const float* intrinsics(MobileGS& g) { return g.mFingerprintIntrinsics; }
};

namespace {

const float kIdentity[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
const float kAnchor[16]   = {0,1,0,0, -1,0,0,0, 0,0,1,0, 0.5f,-0.25f,-2.0f,1};
const float kView[16]     = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,-1.5f,1};
const float kIntr[4]      = {500.f, 500.f, 320.f, 240.f};

void seedMetric(MobileGS& g) {
    cv::Mat descs(4, 32, CV_8U, cv::Scalar(7));
    std::vector<cv::Point3f> pts = {{0,0,0}, {0.1f,0,0}, {0,0.1f,0}, {0.1f,0.1f,0}};
    g.restoreWallFingerprintMetric(descs, pts, kAnchor, kIntr, kView);
    g.setWallPatch(cv::Mat(64, 64, CV_8UC1, cv::Scalar(200)));
    ASSERT_TRUE(MobileGSTestPeer::hasView(g));
    ASSERT_TRUE(MobileGSTestPeer::hasPatch(g));
    ASSERT_EQ(0, std::memcmp(MobileGSTestPeer::anchor(g), kAnchor, sizeof kAnchor));
}

void expectIdentityAnchor(MobileGS& g) {
    float out[16];
    g.getFingerprintAnchor(out); // the value Kotlin composes against
    for (int i = 0; i < 16; ++i) EXPECT_FLOAT_EQ(out[i], kIdentity[i]) << "anchor[" << i << "]";
}

void expectZeroIntrinsics(MobileGS& g) {
    for (int i = 0; i < 4; ++i) EXPECT_FLOAT_EQ(MobileGSTestPeer::intrinsics(g)[i], 0.f);
}

} // namespace

TEST(MobileGSFingerprintState, DescriptorsOnlyRestoreClearsPreviousCoRegistration) {
    MobileGS g;
    seedMetric(g);
    cv::Mat descs(2, 32, CV_8U, cv::Scalar(9));
    g.restoreWallFingerprint(descs, {{1,1,1}, {2,2,2}});
    EXPECT_FALSE(MobileGSTestPeer::hasView(g));
    EXPECT_FALSE(MobileGSTestPeer::hasPatch(g));
    expectIdentityAnchor(g);
    expectZeroIntrinsics(g);
}

TEST(MobileGSFingerprintState, PeerFingerprintDoesNotInheritLocalAnchorOrView) {
    MobileGS host;
    {
        cv::Mat descs(3, 32, CV_8U, cv::Scalar(3));
        host.restoreWallFingerprint(descs, {{0,0,0}, {1,0,0}, {0,1,0}});
    }
    const std::vector<uint8_t> wire = host.exportFingerprint();
    ASSERT_FALSE(wire.empty());

    MobileGS guest;
    seedMetric(guest);
    guest.alignToFingerprint(wire.data(), wire.size());
    EXPECT_FALSE(MobileGSTestPeer::hasView(guest));
    expectIdentityAnchor(guest);
    expectZeroIntrinsics(guest);
}

TEST(MobileGSFingerprintState, MalformedPeerBytesChangeNothing) {
    MobileGS guest;
    seedMetric(guest);
    const uint8_t junk[8] = {0xff, 0xff, 0xff, 0x7f, 0, 0, 0, 0};
    guest.alignToFingerprint(junk, sizeof junk);
    EXPECT_TRUE(MobileGSTestPeer::hasView(guest));
    EXPECT_EQ(0, std::memcmp(MobileGSTestPeer::anchor(guest), kAnchor, sizeof kAnchor));
}
