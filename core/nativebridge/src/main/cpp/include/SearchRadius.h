#ifndef GRAFFITIXR_SEARCH_RADIUS_H
#define GRAFFITIXR_SEARCH_RADIUS_H

#include <cmath>
#include <algorithm>

/**
 * IMPLEMENTATION.md 4.5 — transliteration of
 * `feature/ar/.../anchor/SearchRadius.kt`.
 *
 * The Kotlin file is the reference and carries the full rationale; `SearchRadiusTest` pins the
 * scaling relationships this file has to reproduce. Keep the two in step: every constant here is a
 * pre-registered prior from PARAMETERS.md section 5 that E6/E7/E11 are expected to move, and a value
 * that moves on one side only is a silent divergence between the tested reference and the code that
 * actually runs.
 *
 * Written as a header rather than inline in MobileGS.cpp so the correspondence is one file to one
 * file and a reviewer can diff them side by side.
 */
namespace searchradius {

/** PARAMETERS.md section 5. Mirrors SearchRadius.RHO / MIN_PX / MAX_PX / ERR_GAIN / REPROJ_GAIN. */
constexpr float kRho = 0.05f;
constexpr float kMinPx = 4.0f;
constexpr float kMaxPx = 120.0f;
constexpr float kErrGain = 1.0f;
constexpr float kReprojGain = 2.0f;
/** Negative, never 0 — zero is a legitimate reading for both drift inputs. */
constexpr float kNotMeasured = -1.0f;

/**
 * The single length that stands in for an anisotropic design: geometric mean, not max or min.
 *
 * The radius bounds how far a pose prediction can be wrong, and that error is isotropic in the image
 * whatever the artwork's aspect ratio.
 */
inline float designExtentScalar(float halfW, float halfH) {
    if (halfW <= 0.0f || halfH <= 0.0f) return 0.0f;
    return std::sqrt(halfW * halfH);
}

/**
 * Search radius in pixels for a corroboration match.
 *
 * Degenerate geometry yields kMaxPx, NOT kMinPx. A too-wide search costs precision, which the ratio
 * test still filters; a too-tight one returns no candidates at all and reads downstream as "the wall
 * does not corroborate the design" — indistinguishable from a genuinely unpainted wall.
 *
 * @param poseErrMm  measured pose error in millimetres, negative when not measured.
 * @param reprojErrPx reloc PnP mean inlier reprojection error in pixels, negative when not measured.
 *   Added in pixels and deliberately NOT multiplied by pxPerMeter — it is already an image-space
 *   quantity, and projecting it again would square the perspective. pxPerMeter is in scope right
 *   beside it, which is exactly why this is spelled out.
 */
inline float pixels(float rho, float designHalfWMeters, float designHalfHMeters,
                    float wallDistanceMeters, float focalLengthPx,
                    float poseErrMm, float reprojErrPx = kNotMeasured,
                    float errGain = kErrGain, float reprojGain = kReprojGain,
                    float minPx = kMinPx, float maxPx = kMaxPx) {
    const float halfExtent = designExtentScalar(designHalfWMeters, designHalfHMeters);
    if (halfExtent <= 0.0f || wallDistanceMeters <= 0.0f || focalLengthPx <= 0.0f || rho <= 0.0f) {
        return maxPx;
    }
    if (!std::isfinite(halfExtent) || !std::isfinite(wallDistanceMeters) ||
        !std::isfinite(focalLengthPx)) {
        return maxPx;
    }

    const float pxPerMeter = focalLengthPx / wallDistanceMeters;
    const float scaleTermPx = rho * halfExtent * pxPerMeter;
    const float driftTermPx = (poseErrMm >= 0.0f && std::isfinite(poseErrMm))
        ? errGain * (poseErrMm / 1000.0f) * pxPerMeter : 0.0f;
    const float reprojTermPx = (reprojErrPx >= 0.0f && std::isfinite(reprojErrPx))
        ? reprojGain * reprojErrPx : 0.0f;

    const float r = scaleTermPx + driftTermPx + reprojTermPx;
    return std::min(std::max(r, minPx), maxPx);
}

}  // namespace searchradius

#endif  // GRAFFITIXR_SEARCH_RADIUS_H
