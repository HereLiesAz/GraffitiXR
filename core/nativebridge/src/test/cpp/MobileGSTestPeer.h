// Shared friend accessor for host tests (declared `friend struct MobileGSTestPeer` in MobileGS.h).
// One definition, included by every test translation unit, so the struct is ODR-identical.
#pragma once
#include <opencv2/core.hpp>
#include "MobileGS.h"

struct MobileGSTestPeer {
    static bool hasView(MobileGS& g) { return g.mHasFingerprintView; }
    static bool hasPatch(MobileGS& g) { return !g.mWallPatch.empty(); }
    static const float* anchor(MobileGS& g) { return g.mFingerprintAnchorMatrix; }
    static const float* intrinsics(MobileGS& g) { return g.mFingerprintIntrinsics; }
    static const float* pnpIntrinsics(MobileGS& g) { return g.mPnpIntrinsics; }
    static bool hasDesignPlacement(MobileGS& g) { return g.mHasDesignPlacement; }
    static float designHalfW(MobileGS& g) { return g.mDesignHalfW; }
    static bool hasCapturePhoto(MobileGS& g) { return !g.mCaptureRgb.empty(); }
    static void setRelocReject(MobileGS& g, int v) { g.mLastRelocReject.store(v); }
    static void setCorrobGate(MobileGS& g, int v) { g.mCorrobGate.store(v); }
    static void setGrowOutcome(MobileGS& g, int v) { g.mGrowOutcome.store(v); }
    static long long evalFrameCounter(MobileGS& g) { return g.mEvalSyncFrameCounter.load(); }
};
