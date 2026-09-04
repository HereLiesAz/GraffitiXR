#pragma once
#include <opencv2/opencv.hpp>
#include <opencv2/calib3d.hpp>
#include <mutex>
#include <vector>

/**
 * ARCore-unavailable fallback: a self-contained planar-target tracker, approximating ARCore's
 * 6DoF wall tracking for devices that can't run ARCore at all.
 *
 * Deliberately NOT part of MobileGS — that engine is a dense, carefully-tuned 3D SLAM/reloc
 * pipeline (metric fingerprints, teleological self-grow, PoseFusion) built entirely around an
 * ARCore session supplying real depth/VIO. None of that exists here. This class does the much
 * smaller thing a single-plane fallback actually can: given one reference photo of the
 * traced/painted shape, track a live camera frame against it and report a camera pose relative to
 * that shape, so the same [com.hereliesaz.graffitixr.feature.ar.rendering.OverlayRenderer]
 * quad-drawing path used in real AR mode can render *something* plausible instead of nothing.
 *
 * **On scale — read this before wiring up a caller.** [setReference] takes the reference shape's
 * half-extents (`objectHalfW`/`objectHalfH`) in whatever unit convention the caller's renderer
 * uses for `OverlayRenderer.setExtent`. Every tracked pose comes out in those SAME units, because
 * the pose is solved directly via `cv::solvePnP` against 3D object points built in that exact
 * frame (mirroring `MobileGS::runRelocPass`'s own solvePnPRansac + `SOLVEPNP_IPPE`-refine pattern
 * for its coplanar wall marks) — there is no separate "real-world distance" to guess at, and
 * nothing here reintroduces one. Pass the SAME half-extents the renderer will draw the design
 * quad at and the tracked pose reproduces the reference shape's on-screen size and skew by
 * construction — that's a property of solving PnP directly against object points built in the
 * caller's own units, not an approximation layered on top. What genuinely IS an approximation,
 * because there is no depth sensor or VIO baseline behind it: the pose's absolute distance from
 * the (unknown, un-calibrated) camera has no ground truth to check against, only internal
 * geometric consistency (reprojection error) — so this class targets size/position/skew
 * precision by design but cannot promise the same 6DoF stability under rapid motion that
 * ARCore's sensor fusion provides. That design intent has no automated check behind it: there is
 * no host-buildable or instrumented test exercising this class's actual pose math against a
 * known-answer input (see BACKLOG.md's remediation-plan Phase 7) — the sign error this file's
 * CV->GL conversion once had is exactly the kind of bug that gap let ship silently.
 *
 * The one real ambiguity a coplanar PnP solve carries — the classic "flip" a purely planar target
 * admits, where the plane could equally be tilted the other way and still reproject correctly —
 * is resolved the same way `MobileGS.cpp` resolves it: solve with plain `solvePnPRansac` first,
 * then re-solve the RANSAC inlier set with `SOLVEPNP_IPPE` (which enumerates the flip candidates
 * itself) and keep whichever pose has the lower reprojection error.
 */
class HomographyTracker {
public:
    HomographyTracker();

    /**
     * Detect and store ORB features on a reference photo of the traced/painted shape, replacing
     * any previously-stored reference. Returns false if too few features were found to ever match
     * against (the caller should ask for a better-lit / higher-contrast reference).
     *
     * @param referenceRgba the reference photo, RGBA or RGB.
     * @param objectHalfW,objectHalfH the reference shape's half-extents in the caller's render
     *   units — see the class doc's scale section. Every corner of [referenceRgba] is treated as
     *   lying on the plane Z=0, spanning exactly [-objectHalfW, objectHalfW] x
     *   [-objectHalfH, objectHalfH], with [referenceRgba]'s top-left pixel at
     *   (-objectHalfW, +objectHalfH) — +X right, +Y up, matching OpenGL/render conventions.
     */
    bool setReference(const cv::Mat& referenceRgba, float objectHalfW, float objectHalfH);

    /** True once [setReference] has succeeded and not been [reset]. */
    bool hasReference() const;

    /**
     * Track one live camera frame against the stored reference.
     *
     * @param frameRgba the live frame, RGBA or RGB.
     * @param fx,fy,cx,cy camera intrinsics in pixels, assumed shared with the reference capture.
     * @param outPoseMat16 receives a 4x4 column-major, OpenGL-convention "camera-from-reference-
     *   plane" pose — i.e. usable directly as [com.hereliesaz.graffitixr.feature.ar.rendering.
     *   OverlayRenderer.draw]'s `viewMatrix`, with an anchor quad of the SAME half-extents passed
     *   to [setReference] as the render "world" (see the class doc's scale section). Must point
     *   at 16 writable floats.
     * @param outConfidence receives the RANSAC inlier ratio in [0, 1] — the caller's stand-in for
     *   ARCore's `TrackingState`; a low value means "about to lose the target".
     * @return false if tracking failed this frame (no reference set, too few matches, or PnP
     *   didn't converge) — outPoseMat16/outConfidence are left untouched in that case, so a losing
     *   streak just holds the last good pose on the Kotlin side rather than snapping to garbage.
     */
    bool track(const cv::Mat& frameRgba, float fx, float fy, float cx, float cy,
               float* outPoseMat16, float* outConfidence);

    /** Drops the stored reference. Safe to call at any time. */
    void reset();

    static constexpr int kMinReferenceFeatures = 30;
    static constexpr int kMinMatches = 12;
    static constexpr int kMinInliers = 8;
    static constexpr float kMinInlierRatio = 0.35f;
    static constexpr float kLoweRatio = 0.75f;
    /** Minimum fraction of the frame's width/height the RANSAC inlier set must span, in each
     *  axis independently, before the pose is trusted -- guards against a confidently-wrong pose
     *  from a spatially-degenerate (tightly clustered) inlier set that passes every count-based
     *  gate above. See track()'s spread check for the failure mode this exists to catch. */
    static constexpr float kMinInlierSpreadFrac = 0.25f;
    /** solvePnPRansac's reprojection-error inlier threshold, in pixels. Matches MobileGS's own. */
    static constexpr float kRansacReprojThresholdPx = 8.0f;

private:
    mutable std::mutex mMutex;

    cv::Ptr<cv::ORB> mDetector;
    cv::Ptr<cv::DescriptorMatcher> mMatcher; // BruteForce-Hamming, matches MobileGS's ORB matcher.

    std::vector<cv::KeyPoint> mRefKeypoints;
    cv::Mat mRefDescriptors;
    // Reference keypoints, pre-mapped to 3D object points on the tracked plane (see setReference's
    // doc) — parallel to mRefKeypoints/mRefDescriptors' rows, computed once at reference time so
    // track() never has to redo the pixel->plane mapping per frame.
    std::vector<cv::Point3f> mRefObjectPoints;
    bool mHasReference = false;
};
