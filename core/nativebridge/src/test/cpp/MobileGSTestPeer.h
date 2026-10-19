// Shared friend accessor for host tests (declared `friend struct MobileGSTestPeer` in MobileGS.h).
// One definition, included by every test translation unit, so the struct is ODR-identical.
#pragma once
#include <opencv2/core.hpp>
#include <vector>
#include "MobileGS.h"

struct MobileGSTestPeer {
    static bool hasView(MobileGS& g) { return g.mHasDoodleView; }
    static bool hasPatch(MobileGS& g) { return !g.mWallPatch.empty(); }
    static const float* anchor(MobileGS& g) { return g.mDoodleAnchorMatrix; }
    static const float* intrinsics(MobileGS& g) { return g.mDoodleIntrinsics; }
    static const float* pnpIntrinsics(MobileGS& g) { return g.mPnpIntrinsics; }
    static bool hasDesignPlacement(MobileGS& g) { return g.mHasDesignPlacement; }
    static float designHalfW(MobileGS& g) { return g.mDesignHalfW; }
    static bool hasCapturePhoto(MobileGS& g) { return !g.mCaptureRgb.empty(); }
    static void setRelocReject(MobileGS& g, int v) { g.mLastRelocReject.store(v); }
    static void setCorrobGate(MobileGS& g, int v) { g.mCorrobGate.store(v); }
    static void setGrowOutcome(MobileGS& g, int v) { g.mGrowOutcome.store(v); }
    // Latch a published PnP result as the reloc worker would (counts + seq), for stale-result tests.
    static void setPnpResult(MobileGS& g, int inliers, int matches, long seq) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        g.mPnpInlierCount.store(inliers);
        g.mPnpMatchCount.store(matches);
        g.mPnpResultSeq.store(seq);
    }
    static void setBeforeRelocPublishHook(MobileGS& g, std::function<void()> f) {
        g.mBeforeRelocPublishHook = std::move(f);
    }
    static uint64_t doodleGeneration(MobileGS& g) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        return g.mDoodleGeneration;
    }
    // Share of the registered clean-wall image that has real pixels behind it (-1 = none registered).
    static float cleanWallSupportedFraction(MobileGS& g) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        if (g.mCleanWallValid.empty()) return -1.0f;
        return (float)cv::countNonZero(g.mCleanWallValid) / (float)g.mCleanWallValid.total();
    }
    static size_t targetSetRows(MobileGS& g, int filter) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        return (size_t)filter < g.mTargetSets.size() ? g.mTargetSets[(size_t)filter].pts.size() : 0;
    }
    static long pnpSeq(MobileGS& g) { return g.mPnpResultSeq.load(); }
    static void setWallLastSeen(MobileGS& g, std::vector<long> seen) { std::lock_guard<std::mutex> l(g.mMutex); g.mWallLastSeen = std::move(seen); }
    static void evictGrown(MobileGS& g, size_t n) { std::lock_guard<std::mutex> l(g.mMutex); g.evictLeastSeenGrownLocked(n); }
    static std::vector<cv::Point3f> wallPoints(MobileGS& g) { std::lock_guard<std::mutex> l(g.mMutex); return g.mWallKeypoints3D; }
    static long long evalFrameCounter(MobileGS& g) { return g.mEvalSyncFrameCounter.load(); }
    static void setWallPoints(MobileGS& g, const std::vector<cv::Point3f>& p) { g.mWallKeypoints3D = p; }
    static void grow(MobileGS& g, const glm::mat4& camFromFp, const std::vector<cv::KeyPoint>& kps,
                     const cv::Mat& descs, double fx, double fy, double cx, double cy) {
        g.growMapFromReloc(camFromFp, kps, descs, fx, fy, cx, cy);
    }
    static std::vector<cv::Point3f> mapPoints(MobileGS& g) { return g.mMapPoints3D; }
    static void setWallRegions(MobileGS& g, const std::vector<uint8_t>& r) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        g.mWallRegions = r;
    }
    static size_t wallPointCount(MobileGS& g) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        return g.mWallKeypoints3D.size();
    }
    static size_t wallRegionCount(MobileGS& g) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        return g.mWallRegions.size();
    }
    // What self-grow does to the stored set, without running the detector: append [pts] with [descs].
    static void appendGrown(MobileGS& g, const std::vector<cv::Point3f>& pts, const cv::Mat& descs, uint8_t region) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        for (int i = 0; i < (int)pts.size(); ++i) {
            g.mWallKeypoints3D.push_back(pts[(size_t)i]);
            g.mWallDescriptors.push_back(descs.row(i));
            if (!g.mWallRegions.empty()) g.mWallRegions.push_back(region);
        }
    }
    static void setMap(MobileGS& g, const std::vector<cv::Point3f>& p, const cv::Mat& d,
                       const std::vector<float>& conf, const std::vector<int>& obs) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        g.mMapPoints3D = p; g.mMapDescriptors = d.clone(); g.mMapConfidence = conf; g.mMapObs = obs;
    }
    static std::vector<float> mapConfidence(MobileGS& g) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        return g.mMapConfidence;
    }
    static std::vector<int> mapObs(MobileGS& g) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        return g.mMapObs;
    }
    static void setPnpSpread(MobileGS& g, float v) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        g.mPnpInlierSpread = v;
    }
    static void setCorroborated(MobileGS& g, const std::vector<uint8_t>& c) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        g.mArtworkCorroborated = c;
        g.mArtworkPromoted.assign(c.size(), 1);
    }
    static std::vector<uint8_t> corroborated(MobileGS& g) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        return g.mArtworkCorroborated;
    }
    static std::vector<uint8_t> promoted(MobileGS& g) {
        std::lock_guard<std::mutex> lock(g.mMutex);
        return g.mArtworkPromoted;
    }
};
