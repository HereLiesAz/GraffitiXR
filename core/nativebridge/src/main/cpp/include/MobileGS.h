#pragma once
#include <opencv2/opencv.hpp>
#include <opencv2/geometry.hpp>
#include <opencv2/calib3d.hpp>
#include "SuperPointDetector.h"
#include "DistortionHead.h"
#include "LowLightEnhancer.h"
#include <cmath>
#include <limits>
#include <mutex>
#include <vector>
#include <unordered_map>
#include <string>
#include <thread>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <GLES3/gl3.h>

#include "NativeUtil.h"

class MobileGS {
public:
    MobileGS() {}
    ~MobileGS();

    void initialize(int width, int height);
    // Only viewMat is actually stored (mViewMatrix, read by the rectification pass and the reloc
    // worker's frame snapshot). projMat has no reader anywhere in this engine; see updateCamera's
    // definition for why that half is a logged no-op rather than a real write.
    void updateCamera(float* viewMat, float* projMat);
    void updateLightLevel(float level);
    void updateAnchorTransform(float* transformMat);
    // Deliberately a no-op: neither the angular nor linear velocity this receives is read anywhere
    // in this engine (there is no deblur or motion-compensation consumer). Kept as a callable,
    // logged no-op (same pattern as setStageEnabled below) rather than deleted so existing
    // JNI/Kotlin call sites don't need to change.
    void updateDeviceMotion(float* angularVel, float* linearVel);

    void setArCoreTrackingState(bool isTracking);
    void restoreWallFingerprint(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d);
    // Ingest a fingerprint built from triangulated metric marks (no depth source): also fixes the
    // fingerprint anchor pose and the intrinsics the reloc PnP should use.
    //
    // viewMatrix16 (optional, GL-convention world->camera at capture) enables the plane-guided
    // rectification pass in relocThreadFunc. Without it computeRectifyHomography bails and oblique
    // views get no correction — which was the case for EVERY fingerprint built this way, since only
    // generateFingerprint (the depth path, disabled) ever set it.
    //
    // regions (optional, IMPLEMENTATION.md Phase 2) is one Footprint::Region ordinal per 3D point.
    // The reloc correspondence build excludes INSIDE points — they sit under the artwork and decay
    // as it is painted, so matching against them is what makes accuracy degrade with task progress.
    // EMPTY MEANS ALL BACKBONE, reproducing pre-Phase-2 behaviour exactly for legacy fingerprints.
    void restoreWallFingerprintMetric(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d,
                                      const float* anchorMatrix16, const float* intrinsics4,
                                      const float* viewMatrix16 = nullptr,
                                      const std::vector<uint8_t>& regions = {});
    // Drop the stored wall fingerprint (marks + co-registration). Needed because a fingerprint is
    // otherwise process-lifetime state: the restore calls above only ever REPLACE it, so a project
    // with no fingerprint of its own would keep relocalizing against whatever wall was fingerprinted
    // earlier in the session. Reloc already treats an empty fingerprint as "nothing to match".
    void clearWallFingerprint();
    // Persistent wall *feature* map (lean reloc backbone; docs/RELOC_MAP_DESIGN.md), co-registered to
    // the fingerprint anchor. Restore replaces the stored map (confidence/obs optional). Phase 2a stores
    // it only; reloc matching against it (Phase 2b) is gated separately.
    void restoreWallFeatureMap(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d,
                               const std::vector<float>& confidence, const std::vector<int>& obs,
                               const float* anchorMatrix16, const float* intrinsics4);
    void clearWallFeatureMap();
    int getMapPointCount() const { std::lock_guard<std::mutex> lock(mMutex); return (int)mMapPoints3D.size(); }
    // Phase 3b: pack the live feature map (points/descriptors/confidence/obs + co-registration) into a
    // self-describing little-endian blob for .gxr persistence; empty if there's no map. Race-free (one lock).
    std::vector<uint8_t> exportWallFeatureMap() const;
    // Phase 2b: gate live map-matching in relocThreadFunc. Default OFF — ships inert until validated.
    void setMapRelocEnabled(bool e) { mMapRelocEnabled.store(e, std::memory_order_relaxed); }
    // Phase 3: passively grow the feature map from reloc-locked frames. Default OFF, and independent of
    // the match flag (accumulate without matching, or match a persisted map without growing).
    void setMapBuildEnabled(bool e) { mMapBuildEnabled.store(e, std::memory_order_relaxed); }
    /**
     * Why the last relocalization attempt failed to publish a pose. Ordered by how early the gate
     * sits in the pipeline, so the largest value reached is the furthest the attempt got.
     */
    enum RelocReject {
        kRelocOk = 0,            //!< published a pose
        kRelocNoFingerprint = 1, //!< no wall fingerprint yet (or it carries no 3D points)
        kRelocDisabled = 2,      //!< relocalization switched off
        kRelocNoFeatures = 3,    //!< nothing detected in the live frame, or descriptor type mismatch
        kRelocFewMatches = 4,    //!< fewer than 8 correspondences survived the Lowe ratio test
        kRelocPnpFailed = 5,     //!< solvePnPRansac found no consistent pose
        kRelocFewInliers = 6,    //!< PnP solved but fewer than 6 inliers agreed
    };

    /**
     * Whether a relock is trusted enough to promote new marks into the authoritative fingerprint.
     *
     * This used to be a flat `inliers >= 20`, which could not bootstrap: self-grow is the mechanism
     * that keeps relocalization alive as the original marks get painted over, but a wall whose marks
     * are already half-covered rarely reaches 20 raw inliers, so the fingerprint could only grow when
     * it was already strong — exactly when it needed to least.
     *
     * The ratio is the quality signal (it is what PoseFusion trusts), so a strong ratio now qualifies
     * at a lower absolute count, while a weak ratio still cannot qualify at any count. RANSAC in the
     * reloc PnP remains the geometric backstop for anything this lets through.
     */
    static bool growTrusted(int inliers, int matches, float inlierSpread = kPromotionNotMeasured) {
        // IMPLEMENTATION.md 3.2 — the SPREAD gate, and it comes first because it is the one that
        // catches the state the match test actively endorses. Twenty inliers clustered in one
        // textured corner produce a confident-looking pose whose rotation is barely constrained: the
        // correspondences pin the camera along the viewing ray but let the wall rotate about the
        // cluster at almost no reprojection cost. Everything in a cluster agrees with everything
        // else, so the inlier RATIO is high in exactly that state.
        //
        // Not-measured FAILS here, which is the opposite of SearchRadius's convention, and the
        // asymmetry is deliberate: there a missing reading must not narrow a search; here a missing
        // reading must not authorise a permanent map mutation. Both fail toward the recoverable
        // outcome, which happens to lie in opposite directions.
        if (!(inlierSpread >= 0.0f) || !std::isfinite(inlierSpread)) return false;
        if (inlierSpread < kMinInlierSpread) return false;
        if (inliers >= kBigLockInliers) return true;     // unchanged: a big lock always qualifies
        if (inliers < kMinInliers || matches <= 0) return false;  // floor — PnP noise below this
        return (float)inliers / (float)matches >= kMinInlierRatio;  // above PoseFusion's 0.5 bar
    }

    /**
     * IMPLEMENTATION.md 3.2 — the inliers' bounding box as a fraction of frame area.
     *
     * Bounding box, not convex hull or covariance: the failure being caught is "all the inliers are
     * in one corner", and a box catches that at a fraction of the cost. It over-reports for two
     * distant clusters — which is correct rather than a false pass, since two clusters DO condition
     * the rotation well. The case it must not miss is the single compact cluster, and a box cannot.
     *
     * Returns kPromotionNotMeasured, NOT 0, when it cannot compute an answer. Zero is a real reading
     * meaning every inlier landed on one pixel — the worst-conditioned state this exists to reject —
     * so absence and worst-case must not share a value.
     */
    static float inlierSpreadOf(const std::vector<cv::Point2f>& pts, float frameW, float frameH) {
        if (pts.empty() || !(frameW > 0.0f) || !(frameH > 0.0f) ||
            !std::isfinite(frameW) || !std::isfinite(frameH)) {
            return kPromotionNotMeasured;
        }
        float minX = std::numeric_limits<float>::max(), minY = std::numeric_limits<float>::max();
        float maxX = -std::numeric_limits<float>::max(), maxY = -std::numeric_limits<float>::max();
        int seen = 0;
        for (const auto& p : pts) {
            // A non-finite point would blow the box out to the whole frame and turn the gate into a
            // rubber stamp on exactly the degenerate input it exists to catch.
            if (!std::isfinite(p.x) || !std::isfinite(p.y)) continue;
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
            ++seen;
        }
        if (seen == 0) return kPromotionNotMeasured;
        // Clamped: an inlier can sit fractionally outside the frame after undistortion, and a box
        // larger than the frame would report a spread above 1, which the threshold's units exclude.
        const float w = std::min(1.0f, std::max(0.0f, (maxX - minX) / frameW));
        const float h = std::min(1.0f, std::max(0.0f, (maxY - minY) / frameH));
        return w * h;
    }

    /**
     * PARAMETERS.md section 7. Mirrors `PromotionGate`'s constants; `CorroborationTranslitTest` pins
     * the two sides together, because a value edited here and not there is a silent divergence
     * between the tested reference and the code that actually mutates the map.
     */
    static constexpr int   kBigLockInliers = 20;
    static constexpr int   kMinInliers = 10;
    static constexpr float kMinInlierRatio = 0.6f;
    static constexpr float kMinInlierSpread = 0.04f;
    /** Negative, never 0 — a spread of 0 is a real and maximally bad reading. */
    static constexpr float kPromotionNotMeasured = -1.0f;

    /**
     * IMPLEMENTATION.md 3.4 — Φ, transliterated from `Footprint.of` + `Footprint.classify`.
     *
     * Classifies a point already expressed in the FINGERPRINT frame against the design's placement.
     * Returns kRegionOutside / kRegionBand / kRegionInside, matching the wire ordinals exactly.
     *
     * @param fpFromDesign the design's RIGID model matrix in the fingerprint frame, column-major —
     *   `mDesignFpFromDesign`. Rigid, so the inverse is a transpose plus a rotated translation; the
     *   overlay's scale lives in the half-extents instead, which is `Footprint`'s "scale trap" and
     *   the reason there is no similarity-inverse branch here.
     *
     * Margins default to `FingerprintPartition.DEFAULT_INNER_MARGIN` / `DEFAULT_OUTER_MARGIN`. Note
     * those are the values that SHIPPED, which are not the ones PARAMETERS.md pre-registered (0.05 /
     * 0.15) — the discrepancy is recorded in §4 there, and copying the shipped values here keeps
     * promotion classifying against the same footprint the capture partition used. Two Φs that
     * disagree about the edge would be worse than either margin being wrong.
     */
    static uint8_t classifyInFingerprintFrame(const float* fpFromDesign, float halfW, float halfH,
                                              const cv::Point3f& p,
                                              float innerMargin = 0.04f,
                                              float outerMargin = 0.10f) {
        if (!(halfW > 1e-6f) || !(halfH > 1e-6f)) return kRegionBand;  // degenerate: trust neither set
        // Rigid inverse: R^T, then -R^T t. Written out rather than built as a matrix because only
        // the X and Y rows are needed — Z is the plane normal and Φ discards it by construction.
        const float* m = fpFromDesign;
        const float tx = m[12], ty = m[13], tz = m[14];
        // Row 0 of R^T is column 0 of R = (m[0], m[1], m[2]); likewise row 1.
        const float ix = -(m[0] * tx + m[1] * ty + m[2] * tz);
        const float iy = -(m[4] * tx + m[5] * ty + m[6] * tz);
        const float lx = m[0] * p.x + m[1] * p.y + m[2] * p.z + ix;
        const float ly = m[4] * p.x + m[5] * p.y + m[6] * p.z + iy;
        const float u = lx / halfW;
        const float v = ly / halfH;
        if (!std::isfinite(u) || !std::isfinite(v)) return kRegionBand;
        const float d = std::max(std::fabs(u), std::fabs(v));
        if (d <= 1.0f - innerMargin) return kRegionInside;
        if (d >= 1.0f + outerMargin) return kRegionOutside;
        return kRegionBand;
    }

    /** Hard ceiling on stored wall marks — a memory guard on the self-grow append. */
    static constexpr size_t kMaxWallMarks = 5000;

    /**
     * Why the SPATIALLY-CONSTRAINED corroboration match did or did not run this attempt.
     *
     * The gated path has four preconditions and, until this existed, failing any of them produced
     * exactly the same observable: the corroboration counters read "-1" and the overlay showed a
     * dash. "Phase 4 is off for this project" and "Phase 4 is on but this frame had no pose" are
     * completely different situations with completely different fixes.
     */
    enum CorrobGate {
        kCorrobGated = 0,          //!< ran the gated match
        kCorrobNoArtwork = 1,      //!< no design registered (updatePaintingGuide never fired)
        kCorrobNoPlacement = 2,    //!< no design placement pushed — Phase 4 inactive for this project
        kCorrobNoPose = 3,         //!< relocalization did not lock this frame
        kCorrobBad2d = 4,          //!< artwork 2D positions do not index the descriptors 1:1
        kCorrobNoIntrinsics = 5,   //!< the fingerprint carries no intrinsics to project through
        kCorrobNotRun = 6,         //!< no corroboration attempt reached the decision at all
    };

    /**
     * What the self-grow promotion did, or the reason it declined.
     *
     * Phase 3 is entirely invisible without this. Self-grow is default-OFF, hard-gated, and its
     * refusals are silent by construction — so "the map is not growing" has eight causes that look
     * identical from outside, one of which is simply the switch.
     */
    enum GrowOutcome {
        kGrowNotRun = 0,           //!< the promotion block was not reached this attempt
        kGrowDisabled = 1,         //!< setSelfGrowEnabled(false) — the default, not a fault
        kGrowNoCandidates = 2,     //!< nothing in the frame corroborated the design
        kGrowStaleSeq = 3,         //!< no fresh relock since the last promotion; pose would be stale
        kGrowUntrusted = 4,        //!< the promotion gate refused — inlier spread or match quality
        kGrowNoGeometry = 5,       //!< intrinsics, wall size or plane fit degenerate
        kGrowNoNewPoints = 6,      //!< every candidate back-projected behind the camera or deduped
        kGrowAtCap = 7,            //!< the wall is at kMaxWallMarks
        kGrowPromoted = 8,         //!< marks were written into the map
    };

    /** Last [CorrobGate]; see the enum for why this is worth a channel of its own. */
    int corrobGateReason() const { return mCorrobGate.load(std::memory_order_relaxed); }
    /** Last [GrowOutcome]. */
    int growOutcome() const { return mGrowOutcome.load(std::memory_order_relaxed); }

    /** Sentinel for "no corroboration attempt has produced a measurement yet". */
    static constexpr float kCorroborationUnmeasured = -1.0f;

    /**
     * Per-attempt decay applied to the CORROBORATION channel when an attempt runs and yields no
     * usable measurement — the wall left the frame, the head refused, the patch is missing. It
     * expresses "my last reading is getting stale", which is a statement about confidence and never
     * was one about how much of the mural is painted.
     */
    static constexpr float kCorroborationDecay = 0.9f;

    /**
     * IMPLEMENTATION.md 4.7 — the two Lowe ratios, named apart so E7 can sweep them independently.
     *
     * They were the same literal `0.75f` written at four call sites, which made them look like one
     * decision. They are not. The reloc match is a GLOBAL search over the backbone with no prior — as
     * are the two map matches, which is why those keep `kRelocLoweRatio` — where a
     * loose ratio admits false correspondences that RANSAC then has to reject; the corroboration
     * match after Phase 4 searches a candidate set roughly 0.2% the size, where the false-positive
     * population has already collapsed and the same threshold throws away true matches for nothing.
     * Phase 4's entire justification is that the constraint lets this one be LOOSENED — so it has to
     * be a separate number, or the effect cannot be measured.
     *
     * Both are pre-registered priors (PARAMETERS.md section 5). `kRelocLoweRatio` is the value that
     * shipped; `kCorrobLoweRatio` is the paper's suggested 0.85, and E7 sets it for real.
     */
    static constexpr float kRelocLoweRatio = 0.75f;
    static constexpr float kCorrobLoweRatio = 0.85f;

    /**
     * How many separate gated attempts must corroborate a design feature before it counts toward
     * painting progress.
     *
     * Progress accumulates and never decays, which is what the channel promises — but a
     * monotone counter with no false-positive bound saturates on noise given enough ticks. The
     * reloc loop runs at 5 Hz locked, so even one spurious match per attempt would walk a
     * 1500-feature design to "100% painted" in minutes on a bare wall.
     *
     * Two, not one, and not five. One is the unbounded case. Two costs a genuinely painted feature
     * nothing — it corroborates on every attempt it is in view for, so it confirms on the next tick
     * — while removing the entire class of TRANSIENT false positives, which is the class that
     * ratchets. It does not defend against a persistent false positive: a wall feature that really
     * does sit near a design feature's prediction and really does win the ratio test will confirm,
     * and no counter threshold changes that. That is a limit of descriptor corroboration, not of
     * this constant, and it is why E6 measures progress against ground truth rather than trusting
     * it.
     */
    static constexpr uint8_t kCorrobConfirmations = 2;

    /** Last [RelocReject]. */
    int lastRelocReject() const { return mLastRelocReject.load(std::memory_order_relaxed); }
    /** Correspondences built by the last attempt, successful or not. */
    int lastRelocMatches() const { return mLastRelocMatches.load(std::memory_order_relaxed); }
    /** RANSAC inliers from the last attempt, successful or not (0 if PnP never ran). */
    int lastRelocInliers() const { return mLastRelocInliers.load(std::memory_order_relaxed); }
    /**
     * Features detected in the last live frame, before any matching. Separates "this frame has no
     * texture to work with" from "plenty of texture, none of it the registered wall" — a low match
     * count means opposite things in those two cases.
     */
    int lastRelocDetected() const { return mLastRelocDetected.load(std::memory_order_relaxed); }
    /** Obliquity (deg) measured by the rectification pass, or -1 when it wasn't eligible. */
    int lastRelocObliquityDeg() const { return mLastRelocObliquityDeg.load(std::memory_order_relaxed); }
    /** Extra correspondences the rectification pass contributed to the last attempt. */
    int lastRelocRectifiedCorr() const { return mLastRelocRectifiedCorr.load(std::memory_order_relaxed); }
    /**
     * Stored fingerprint points in F_out — the backbone the reloc PnP is allowed to use — or -1 when
     * no attempt has got far enough to look. An unpartitioned fingerprint reports its full point
     * count, not zero, per the zero-length-regions rule.
     */
    int lastRelocBackboneFeatures() const { return mLastRelocBackboneFeatures.load(std::memory_order_relaxed); }
    /** Correspondences built against F_out, counted apart from the total; -1 when not measured. */
    int lastRelocBackboneMatches() const { return mLastRelocBackboneMatches.load(std::memory_order_relaxed); }
    /** RANSAC inliers that came from F_out points; -1 when PnP never produced an inlier set. */
    int lastRelocBackboneInliers() const { return mLastRelocBackboneInliers.load(std::memory_order_relaxed); }

    /**
     * IMPLEMENTATION.md 4.6 — the corroboration match's own counts, so the effect Phase 4 claims can
     * be seen rather than argued about.
     *
     * `corrobPredicted` is how many design features the current pose puts inside the frame;
     * `corrobMatched` is how many of those the wall answered for. Both -1 until a GATED attempt has
     * run: the global fallback path measures a different thing (every descriptor of the design,
     * framing included) and reporting its numbers here would make the two look comparable.
     *
     * Zero predicted is a real reading — the artist is looking away from the design — and is the
     * state 4.8 exists to keep distinct from zero matched.
     */
    int corrobPredicted() const { return mCorrobPredicted.load(std::memory_order_relaxed); }
    int corrobMatched() const { return mCorrobMatched.load(std::memory_order_relaxed); }
    /**
     * Predicted-visible features the gated match REFUSED to test, because their neighbourhood held
     * a single candidate and Lowe's ratio has nothing to divide by. -1 when no gated attempt ran.
     *
     * Published rather than logged because it is the number that decides whether the phase works.
     * These skips deflate `corrobMatched` without touching `corrobPredicted`, so a radius that is
     * too tight does not announce itself as an error — it announces itself as a wall that has
     * stopped corroborating, which is indistinguishable from an unpainted one. At the `MIN_PX`
     * floor a sparse frame can skip most of what it predicted; with this on the channel that is a
     * reading E6 can act on instead of an unknown.
     */
    int corrobLoneSkips() const { return mCorrobLoneSkips.load(std::memory_order_relaxed); }
    /**
     * IMPLEMENTATION.md 3.3 — how much of the frame the last lock's inliers spanned, as a fraction
     * of frame area, or -1 when not measured.
     *
     * Published because the promotion it gates is irreversible and otherwise invisible: a refusal
     * here looks identical to self-grow simply not having fired, and E5 has to tell those apart to
     * say whether the term is too strict.
     */
    float lastRelocInlierSpread() const { return mLastRelocInlierSpread.load(std::memory_order_relaxed); }

    /** Search radius the last gated attempt used, in pixels, or -1 when no gated attempt has run. */
    float corrobSearchRadiusPx() const { return mCorrobSearchRadiusPx.load(std::memory_order_relaxed); }
    /**
     * Mean reprojection error over the last lock's PnP inliers, in pixels, or -1 when not measured.
     *
     * Feeds the search radius' drift term (SearchRadius.h) and is a diagnostic in its own right.
     * Bounded above by the RANSAC inlier threshold by construction, which is why the gain applied to
     * it is not 1.0 — see `SearchRadius.REPROJ_GAIN`.
     */
    float lastRelocReprojPx() const { return mLastRelocReprojPx.load(std::memory_order_relaxed); }

    /**
     * IMPLEMENTATION.md 4.5 — where the design sits, so the corroboration match can predict where
     * each of its features should appear.
     *
     * @param fpFromDesign16 the design's model matrix expressed in the WALL FINGERPRINT frame,
     *   column-major. This is exactly `FingerprintPartition.partition`'s `designInPointFrame`
     *   (`captureAnchorCam * rigidModelAnchorLocal`) — the same composition Phi is classified with,
     *   deliberately, because a second derivation of the same quantity is a second chance to get the
     *   frame wrong. RIGID: the overlay's scale is folded into the extents, not this matrix.
     * @param halfW,halfH the design's effective (scale-included) half-extents in metres.
     *
     * Pass a null pointer or a non-positive extent to clear the placement, which returns the
     * corroboration match to the global search it did before Phase 4.
     */
    void setDesignPlacement(const float* fpFromDesign16, float halfW, float halfH);

    void scheduleRelocCheck(const cv::Mat& colorFrame);
    /**
     * Cheap pre-check for the three conditions under which scheduleRelocCheck() drops the frame:
     * relocalization disabled, no wall fingerprint to match against yet, or the reloc worker still
     * busy with the previous request.
     *
     * Callers convert a raw camera frame to RGB (and rotate it) before they can schedule it, which
     * is the single most expensive thing on the GL render thread. Ask this first so that work is
     * only paid for on frames the worker will actually take — during scanning there is no
     * fingerprint yet, so every one of those conversions used to be built and thrown away.
     */
    bool relocWantsFrame();
    void getAnchorTransform(float* outMat16) const;
    void getRelocResult(float* out19) const;       // [0..15]=pnpMat,16=inliers,17=matches,18=seq
    void getFingerprintAnchor(float* out16) const;
    void setArtworkFingerprint(const cv::Mat& composite, const uint8_t* depthData, int depthW, int depthH, int depthStride, const float* intrinsics4, const float* viewMat16);
    // Detect the same features generateFingerprint would (SuperPoint/ORB-1000, masked) and return their
    // 2D positions in image pixels — for a truthful "what anchors the fingerprint" curation overlay.
    void getFingerprintKeypoints(const cv::Mat& image, const cv::Mat& mask, std::vector<cv::Point2f>& out);
    // Teleological self-grow (default ON): promotes validated new marks into the live reloc
    // fingerprint so snap-back survives the original reference being painted over. Mutates the
    // authoritative set but is hard-guarded (fresh+confident relock, gatekeeper-validated, plane-fit,
    // re-projection dedup, per-relock + total caps, RANSAC backstop). Toggleable via the UI.
    void setSelfGrowEnabled(bool e) { mSelfGrowEnabled.store(e, std::memory_order_relaxed); }
    // Live wall-fingerprint size — diagnostic for relocalization health and watching self-grow.
    int getWallKeypointCount() const { std::lock_guard<std::mutex> lock(mMutex); return (int)mWallKeypoints3D.size(); }
    // SuperPoint detect+describe for one image (gray + CLAHE applied inside, matching the reloc path) so
    // the depth-off triangulated fingerprint can be built from SuperPoint (CV_32F) instead of ORB. False
    // if the model isn't loaded or nothing was found.
    bool getSuperPointFeatures(const cv::Mat& image, std::vector<cv::KeyPoint>& kps, cv::Mat& descs);

    struct FingerprintData {
        std::vector<cv::KeyPoint> keypoints;
        std::vector<float> points3d;
        cv::Mat descriptors;
    };
    FingerprintData generateFingerprint(const cv::Mat& image, const cv::Mat& mask, const uint8_t* depthData, int depthW, int depthH, int depthStride, const float* intrinsics, const float* viewMat);

    bool loadSuperPoint(const std::vector<uchar>& onnxBytes);
    bool loadDistortionHead(const std::vector<uchar>& onnxBytes) { return mDistortionHead.load(onnxBytes); }
    // Canonical fingerprint patch (the marks) the distortion head compares the live crop against.
    // Stored as a raw 256x256 gray (NO CLAHE — the head's frozen SuperPoint was trained on raw gray).
    void setWallPatch(const cv::Mat& img);
    bool loadLowLightEnhancer(const std::vector<uchar>& onnxBytes);
    // Deliberately a no-op: mScreenWidth/mScreenHeight (the only state this used to write) are never
    // read anywhere in this engine -- there is no viewport-dependent computation here to size. Kept
    // as a callable, logged no-op (same pattern as setStageEnabled below) rather than deleted so
    // existing JNI/Kotlin call sites don't need to change; a caller relying on this to configure
    // rendering will see a warning in logcat instead of a silently-ignored request.
    void setViewportSize(int width, int height);
    // Eval: fill out[kStageCount] with average ms/stage since last reset, then reset accumulators.
    // Only index 4 (pnpReloc) is ever actually timed in this file today -- indices 0-3
    // (voxelUpdate/voxelKeyframe/surfaceMesh/draw) name stages of a splat-rendering pipeline that
    // does not exist in this engine, so there is no work here to instrument. Those four report -1.0f
    // ("not measured"), the same sentinel the rest of this header uses, rather than a fabricated 0.0.
    void getStageTimingsAndReset(float* out);
    // Deliberately a no-op: no stage's work is actually gated by this flag (see mStageEnabled's
    // removal below). Kept as a callable, logged no-op rather than deleted so existing JNI/Kotlin
    // call sites don't need to change; a caller that expects this to skip work will see a warning
    // in logcat instead of a silently-ignored request.
    void setStageEnabled(int stage, bool enabled);
    void setRelocEnabled(bool enabled);

    /**
     * EVALUATION.md 3.1 / IMPLEMENTATION.md 6a.4 — fix OpenCV's RNG so a replayed run is
     * reproducible. `solvePnPRansac` draws random samples, so two replays of the same recording can
     * differ and an A/B of two parameter values reports scheduling noise as an effect.
     *
     * A NEGATIVE seed (the default) means "do not touch the RNG", which is the production path:
     * the feature is inert unless an eval run explicitly turns it on. It is an evaluation
     * affordance, not a behaviour change — a fixed seed in production would make every user's
     * RANSAC draw the identical sample sequence forever.
     */
    void setEvalRngSeed(long long seed);
    /**
     * EVALUATION.md 3.1 — enable inline relocalization at one pass per [everyN] frames.
     *
     * @param everyN clamped to at least 1. Pass 0 or negative and you would get either a divide by
     *   zero or a mode that never runs; both are worse than the honest floor, and a silent
     *   never-runs would look exactly like relocalization being broken.
     *
     * Disabling resets the frame counter, so two runs in one process start their cadence from the
     * same phase rather than wherever the previous run left off — otherwise "every 5th frame" would
     * mean a different five frames per run, which is the non-determinism this exists to remove.
     */
    void setEvalSyncReloc(bool enabled, int everyN);
    /** True when inline relocalization is active — for the run-identity sidecar's sync/async field. */
    bool isEvalSyncReloc() const { return mEvalSyncReloc.load(std::memory_order_relaxed); }
    /** The cadence in force, or 0 when sync mode is off. Reported alongside the flag. */
    int evalSyncEveryN() const {
        return mEvalSyncReloc.load(std::memory_order_relaxed)
            ? mEvalSyncEveryN.load(std::memory_order_relaxed) : 0;
    }
    // Deliberately a no-op: there is no gaussian-splat mapper in this engine for this to pause (the
    // reloc/fingerprint pipeline is not "mapping" and is never gated by this flag). Kept as a
    // callable, logged no-op (same pattern as setStageEnabled below) rather than deleted so the
    // seven ArViewModel call sites don't need to change; a caller relying on this to actually pause
    // work will see a warning in logcat instead of a silently-ignored request.
    void setMappingPaused(bool paused);

    /**
     * How much of the registered design the wall now answers for: a PROGRESS measurement, on the
     * timescale of hours, roughly monotonic. For the user's progress readout.
     *
     * Never decayed. It used to be, which conflated it with the signal below.
     */
    float getPaintingProgress() const { return mPaintingProgress.load(std::memory_order_relaxed); }

    /**
     * How much the wall corroborates the design RIGHT NOW: a per-attempt CONFIDENCE, on the
     * timescale of frames, moving in both directions. This is what PoseFusion should scale its
     * correction strength by.
     *
     * Split from painting progress because one scalar cannot mean both "the mural is 60% painted"
     * and "I trust this frame". They move on completely different timescales, and the decay that
     * belongs to the second was being applied to the first — so three bad frames read as the mural
     * being un-painted, and the 0.9-per-tick decay made that persist for seconds afterwards.
     *
     * Returns a negative value when no corroboration attempt has produced a measurement yet, which
     * is a different state from "attempted and found nothing" (0.0). Callers that need a plain
     * [0,1] should clamp; PoseFusion's CONF_FLOOR already treats 0 as the conservative floor.
     */
    float getCorroborationConfidence() const {
        return mCorroborationConfidence.load(std::memory_order_relaxed);
    }

    // Collaboration methods
    std::vector<uint8_t> exportFingerprint();
    void alignToFingerprint(const uint8_t* data, size_t size);

    void destroy();
    std::mutex& getMutex() { return mMutex; }

private:
    void relocThreadFunc();
    /**
     * EVALUATION.md 3.1 — one relocalization attempt over one frame, callable either from the
     * background worker or inline from the caller in eval sync mode.
     */
    void runRelocPass(const cv::Mat& frame, const float* relocView);
    // Teleological self-grow (gatekeeper stage): measure how much of the registered artwork base is now
    // corroborated by real wall content in the clean camera frame -> mPaintingProgress. Read-only on the
    // reloc fingerprint; the promotion step (adding validated new marks) is staged separately.
    /**
     * @param preKps,preDescs features already detected on [grayClean] by the caller. The reloc thread
     *        has just run the detector over this exact frame, and SuperPoint is an ONNX forward pass —
     *        detecting it a second time doubled the per-cycle cost for an identical result. Reused
     *        only when the descriptor type matches the artwork's (a mismatch can't knnMatch anyway,
     *        so it re-detects with the right detector). Pass nullptr to always detect.
     */
    void tryUpdateFingerprint(const cv::Mat& grayClean,
                              const std::vector<cv::KeyPoint>* preKps = nullptr,
                              const cv::Mat* preDescs = nullptr);
    // Plane-guided rectification: homography (current-image <-> fingerprint-image) from the wall plane
    // and the VIO baseline between the current and fingerprint-capture views, plus the viewing
    // obliquity in degrees. False if no fingerprint view is stored or the geometry is degenerate.
    bool computeRectifyHomography(const float* viewCur16, cv::Mat& Hcur_fp, cv::Mat& Hfp_cur, double& obliquityDeg);
    // Phase 3 passive builder: on a reloc lock, back-project the frame's features onto the wall plane
    // (fit from the fingerprint points) to get 3D points in the fingerprint frame, associate to the map
    // by descriptor (bump confidence) or add new (capped). Co-registers the map to the fingerprint anchor.
    void growMapFromReloc(const glm::mat4& camFromFp, const std::vector<cv::KeyPoint>& kps,
                          const cv::Mat& descs, double fx, double fy, double cx, double cy);

    mutable std::mutex mMutex;
    std::atomic<bool> mIsArCoreTracking{false};

    cv::Ptr<cv::ORB> mFeatureDetector;
    cv::Ptr<cv::DescriptorMatcher> mMatcher;    // BruteForce-Hamming for ORB (CV_8U)
    cv::Ptr<cv::DescriptorMatcher> mL2Matcher;  // BruteForce-L2 for SuperPoint (CV_32F)
    SuperPointDetector mSuperPoint;
    DistortionHead mDistortionHead;
    LowLightEnhancer mEnhancer;
    static constexpr float kLowLightThreshold = 0.35f;

    cv::Mat mWallDescriptors;
    std::vector<cv::Point3f> mWallKeypoints3D;
    // IMPLEMENTATION.md Phase 2 — the footprint partition, parallel to mWallKeypoints3D. One
    // Footprint::Region ordinal per point. EMPTY means "no partition" and is read as all-backbone,
    // so a pre-Phase-2 fingerprint relocalizes exactly as it does on main. Always either empty or
    // the same size as mWallKeypoints3D; the JNI layer drops a mismatched array rather than let a
    // short one be indexed by point index.
    std::vector<uint8_t> mWallRegions;
    // Ordinals must match Footprint.Region's declaration order (INSIDE, BAND, OUTSIDE). Kotlin
    // writes the byte and C++ reads it, so the two enums are a wire format with no compiler to
    // check them; FootprintRegionWireTest pins the Kotlin side against these values.
    static constexpr uint8_t kRegionInside = 0;
    static constexpr uint8_t kRegionBand = 1;
    static constexpr uint8_t kRegionOutside = 2;
    cv::Mat mArtworkDescriptors;
    std::vector<cv::Point3f> mArtworkKeypoints3D;
    // IMPLEMENTATION.md 4.5 — the artwork features' 2D positions in the composite the descriptors
    // were detected on, kept 1:1 with mArtworkDescriptors THROUGH the depth-path row filter in
    // setArtworkFingerprint. mArtworkKeypoints3D is not usable for this: it is empty on the shipping
    // path (the design composite carries no depth) and, when depth does exist, it is expressed in
    // the artwork CAPTURE camera's frame, which has no relation to the wall fingerprint frame the
    // reloc pose is in. Predicting where a design feature should appear goes through the design's
    // placement instead, which is a frame the app actually knows.
    std::vector<cv::Point2f> mArtworkKeypoints2D;
    // Pixel size of that composite; the design-plane mapping is parametric in it, so a design
    // re-registered at a different resolution stays correct without rescaling the keypoints.
    int mArtworkImageW = 0;
    int mArtworkImageH = 0;
    // One flag per artwork descriptor: has the wall EVER answered for this feature? Painting
    // progress is the popcount over this, not the current frame's match count.
    //
    // Before Phase 4 the instantaneous ratio was already the whole design's, so a frame that saw
    // everything reported everything. The gated match only ever looks at the part of the design in
    // view, so the instantaneous ratio would become a statement about where the artist is standing.
    // Accumulating is also closer to what getPaintingProgress() already promises — "on the timescale
    // of hours, roughly monotonic. Never decayed." Cleared whenever a new artwork is registered,
    // because every prior reading was against a different target.
    std::vector<uint8_t> mArtworkCorroborated;
    // Bumped whenever the artwork is replaced or cleared. tryUpdateFingerprint snapshots the
    // descriptors under the lock, spends milliseconds matching outside it, and then merges its
    // result back in; without this it would merge into whatever artwork is registered by then. The
    // sizes alone are not enough — a design swapped for one with the same feature count would pass a
    // length check and corrupt the accumulator with another target's hits.
    long mArtworkGeneration = 0;
    std::atomic<float> mPaintingProgress{0.0f};
    // -1 = never measured, which is NOT the same as 0.0 = measured and found nothing. Zero is a
    // legitimate reading here, so it cannot double as the "no data yet" sentinel.
    std::atomic<float> mCorroborationConfidence{kCorroborationUnmeasured};

    /**
     * Age the corroboration reading after an attempt that produced nothing usable.
     *
     * A never-measured channel stays never-measured: decaying the -1 sentinel would walk it toward
     * zero and quietly turn "no data" into "measured, found nothing" — the exact class of bug that
     * shipped in mLastRelocReject, where 0 doubled as both a valid code and the default.
     */
    void decayCorroboration() {
        const float c = mCorroborationConfidence.load(std::memory_order_relaxed);
        if (c < 0.0f) return;
        mCorroborationConfidence.store(c * kCorroborationDecay, std::memory_order_relaxed);
    }

    // --- Persistent wall feature map (lean reloc backbone; docs/RELOC_MAP_DESIGN.md) ---
    // Co-registered to the fingerprint anchor. Phase 2a stores it; reloc matching (Phase 2b) is gated
    // separately, so today this is inert state with no effect on relocalization.
    cv::Mat mMapDescriptors;
    std::vector<cv::Point3f> mMapPoints3D;
    std::vector<float> mMapConfidence;
    std::vector<int> mMapObs;
    float mMapAnchorMatrix[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    float mMapIntrinsics[4] = {0,0,0,0};
    // Phase 2b flag: when true, relocThreadFunc also matches the frustum-gated map and merges those
    // correspondences into PnP. Default OFF so the map has zero effect on reloc until device-validated.
    std::atomic<bool> mMapRelocEnabled{false};
    std::atomic<bool> mMapBuildEnabled{false};

    float mAnchorMatrix[16];

    // --- Pose fusion (Sub-project B): reloc result published for Kotlin to compose correctly ---
    float mPnpCamFromFpWorld[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    std::atomic<int> mPnpInlierCount{0};
    std::atomic<int> mPnpMatchCount{0};
    std::atomic<long> mPnpResultSeq{0};
    float mFingerprintAnchorMatrix[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    // fx,fy,cx,cy the wall fingerprint's 3D points were built with; {0,..} => unset (use a default).
    float mFingerprintIntrinsics[4] = {0,0,0,0};
    // IMPLEMENTATION.md 4.5 — the design's rigid pose in the FINGERPRINT frame plus its
    // scale-included half-extents. Guarded by mMutex like everything else here, and false until
    // Kotlin has pushed a placement: absent means the corroboration match falls back to the global
    // search, which is Phase 4 turned off rather than Phase 4 guessing.
    float mDesignFpFromDesign[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    float mDesignHalfW = 0.0f;
    float mDesignHalfH = 0.0f;
    bool mHasDesignPlacement = false;
    // VIO view matrix at fingerprint-capture time + flag; used to rectify oblique live views to the
    // fingerprint's frontal frame before matching (perspective-robust matching). False for fingerprints
    // restored without a capture view (rectification is then skipped — plain matching still runs).
    float mFingerprintViewMatrix[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    bool mHasFingerprintView = false;
    // Teleological self-grow: default OFF + the last reloc seq we grew from (only grow on a fresh,
    // confident relock so promoted marks are placed with a current pose, never a stale one).
    //
    // OFF, not ON. This is the only mechanism in the engine that permanently mutates the
    // authoritative reloc fingerprint, so a bad promotion is not a transient error — it is written
    // into the map and compounds. tryUpdateFingerprint's own comment says it "stays OFF unless
    // explicitly enabled"; the initializer said otherwise and the header won, which meant every
    // release build promoted unsupervised with no user-facing switch to stop it.
    std::atomic<bool> mSelfGrowEnabled{false};

    /**
     * EVALUATION.md 3.1 — run relocalization INLINE on the calling thread, one pass every Nth
     * frame, instead of handing frames to the background worker.
     *
     * The second of the three named sources of replay non-determinism. RANSAC's RNG is seeded
     * (mEvalRngSeed) and the frame order is fixed by the recording, but the worker's
     * `locked ? 200 : 60` ms sleep means WHICH frames it receives is a scheduling outcome. Two
     * replays of one recording therefore sample it differently, and a parameter A/B silently
     * becomes a comparison of two different frame subsets.
     *
     * Off by default and gated at the JNI call site to debuggable builds, because this is an
     * evaluation affordance and not a behaviour change: run inline and the reloc cost lands on the
     * caller's thread at a cadence no real device would choose. EVALUATION.md is explicit that both
     * are reported — the async numbers are what users get, the sync numbers are what is comparable.
     */
    std::atomic<bool>     mEvalSyncReloc{false};
    std::atomic<int>      mEvalSyncEveryN{1};
    /**
     * Frames seen since sync mode was enabled, for the every-Nth decision. Not atomic-incremented
     * for correctness across threads — scheduleRelocCheck is called from the one render thread —
     * but atomic so enabling the mode from another thread cannot tear it.
     */
    std::atomic<long long> mEvalSyncFrameCounter{0};

    /** Fixed RANSAC seed for reproducible replay, or <0 for "leave the RNG alone" (default). */
    std::atomic<long long> mEvalRngSeed{-1};
    long mLastGrowSeq = 0;
    cv::Mat mWallPatch; // raw 256x256 gray canonical patch for the distortion head (desc_fp source)
    // VIO view snapshot captured alongside the reloc frame, so the rectifying warp matches that frame.
    float mRelocViewMatrix[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    // Written by updateLightLevel() on the caller's thread, read (unlocked) by the reloc worker
    // thread in runRelocPass / getSuperPointFeatures / getFingerprintKeypoints / generateFingerprint
    // to decide whether to run the low-light enhancer. Atomic, like every other piece of cross-thread
    // scalar state in this class, rather than mMutex: the reads are on a hot per-frame path and this
    // is a single float with no invariant linking it to anything else the mutex protects.
    std::atomic<float> mLightLevel{1.0f};

    float mViewMatrix[16]; // the only camera state anything reads; see updateCamera's definition.

    // --- Evaluation instrumentation (Sub-project A) ---
    // Accumulated wall-time per stage and a sample count, for averaging. Indexes match the Kotlin
    // stage contract: 0=voxelUpdate,1=voxelKeyframe,2=surfaceMesh,3=draw,4=pnpReloc. Only index 4 is
    // ever populated by a real StageTimer -- see getStageTimingsAndReset.
    static constexpr int kStageCount = 5;
    std::atomic<double> mStageAccumMs[kStageCount] = {};
    std::atomic<uint64_t> mStageSamples[kStageCount] = {};

    std::thread             mRelocThread;
    std::mutex              mRelocMutex;
    std::condition_variable mRelocCv;
    std::atomic<bool>       mRelocRunning{false};
    std::atomic<bool>       mRelocRequested{false};
    std::atomic<bool>       mRelocEnabled{true};

    /**
     * Why the most recent relocalization attempt did not publish a pose (a RelocReject value), and
     * the correspondence/inlier counts from that attempt whether or not it succeeded.
     *
     * mPnpMatchCount / mPnpInlierCount only update on SUCCESS, so before the first lock they read
     * zero and a failing pipeline is indistinguishable from an idle one. These always reflect the
     * last attempt, so the diagnostics can say which gate is being missed and by how much.
     */
    // NOT {0}: zero is kRelocOk, so a default-constructed engine would report a successful lock
    // before a single attempt had run and the overlay would read LOCKED on a wall it had never seen.
    // kRelocNoFingerprint is the truthful starting state — there is no fingerprint until one is built.
    std::atomic<int>        mLastRelocReject{kRelocNoFingerprint};
    std::atomic<int>        mLastRelocMatches{0};
    std::atomic<int>        mLastRelocInliers{0};
    std::atomic<int>        mLastRelocDetected{0};
    // Obliquity (degrees) the rectification pass measured, or -1 when it wasn't eligible; and how many
    // extra correspondences it contributed.
    std::atomic<int>        mLastRelocObliquityDeg{-1};
    std::atomic<int>        mLastRelocRectifiedCorr{0};
    // IMPLEMENTATION.md 2.11 — the backbone (F_out) counts, all three -1 for "not measured".
    // They CANNOT default to 0 the way the totals above do. With the partition landed, a zero
    // backbone is a real reading with a specific meaning — the artwork covers the whole wall, so
    // reloc has nothing left to bootstrap from, which is the failure Phase 2's risk section names
    // and ArUiState.backboneTooSmall warns about. If zero also meant "never sampled", the one
    // condition these counters exist to expose would be indistinguishable from an idle thread.
    std::atomic<int>        mLastRelocBackboneFeatures{-1};
    std::atomic<int>        mLastRelocBackboneMatches{-1};
    std::atomic<int>        mLastRelocBackboneInliers{-1};
    // IMPLEMENTATION.md 4.6 — the corroboration match's counts and the radius it used. All -1 for
    // "no gated attempt has run", following the same rule as the trio above: zero predicted is a
    // real reading (the artist is not looking at the design) and cannot double as the default.
    std::atomic<int>        mCorrobPredicted{-1};
    std::atomic<int>        mCorrobMatched{-1};
    std::atomic<int>        mCorrobLoneSkips{-1};
    std::atomic<float>      mCorrobSearchRadiusPx{-1.0f};
    // Mean reprojection error over the last lock's PnP inliers, in pixels. -1 = not measured, and it
    // stays -1 on every path that does not reach a refined inlier set.
    std::atomic<float>      mLastRelocReprojPx{-1.0f};
    // IMPLEMENTATION.md 3.2 — the last attempt's inlier bounding box as a fraction of frame area,
    // or -1 for "not measured". Published from runRelocPass, which is the only scope holding the
    // inlier POINTS; the self-grow gate sees counts through atomics and would otherwise be blind to
    // the geometry that actually conditions the pose.
    std::atomic<float>      mLastRelocInlierSpread{kPromotionNotMeasured};
    // Why each stage did or did not run. Both default to "not run", which is distinct from every
    // real outcome — the same rule the -1 counters follow, in enum form.
    std::atomic<int>        mCorrobGate{kCorrobNotRun};
    std::atomic<int>        mGrowOutcome{kGrowNotRun};
    cv::Mat                 mRelocColorFrame;
};
