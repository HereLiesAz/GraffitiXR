#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

/**
 * Transliteration of SphereSLAM's `reloc/DepthScaleFit.kt` (the reference; its tests and KDoc carry
 * the rationale). Calibrates MiDaS relative inverse depth to the doodle's metric scale so
 * MobileGS::growMapFromReloc can place off-wall features radially (the library's
 * `RadialDepthPlacement`).
 *
 * MiDaS is affine on INVERSE depth: `invDepth ≈ a·(1/Z) + b`. Wall features give known
 * `(1/Z, invDepth)` pairs. Plain least squares lets one bad depth pixel or mismatch drag the line,
 * so the fit is seeded by a deterministic least-median-of-squares search over sample pairs (up to
 * ~50% outliers), then refined by iterative trimmed least squares within 3 robust sigmas
 * (1.4826·MAD). Degeneracy is judged scale-invariantly: std(1/Z)/mean(1/Z) must reach 1e-3.
 *
 * Header-only and framework-free so the host tests (src/test/cpp/DepthScaleFitTest.cpp) pin it.
 */
namespace graffitixr {

struct DepthFit {
    float a = 0.f;
    float b = 0.f;
    bool ok = false;

    /** Camera-axis depth for an inverse-depth sample; <= 0 when behind the camera / at infinity. */
    float depthFor(float invDepth) const {
        if (!ok || a <= 0.f || !std::isfinite(invDepth)) return -1.f;
        const float invZ = (invDepth - b) / a;
        if (invZ <= 1e-4f) return -1.f;
        return 1.f / invZ;
    }
};

namespace depthfit_detail {

constexpr double kMinRelativeSpread = 1e-3;
constexpr double kOutlierSigmas = 3.0;
constexpr long long kMaxSeedPairs = 256;
constexpr int kMaxTrimIterations = 5;

inline double median(std::vector<double> v) {
    if (v.empty()) return 0.0;
    std::sort(v.begin(), v.end());
    const size_t mid = v.size() / 2;
    return (v.size() % 2 == 1) ? v[mid] : 0.5 * (v[mid - 1] + v[mid]);
}

inline bool degenerate(const std::vector<double>& xs, const std::vector<int>& idx) {
    if (idx.size() < 2) return true;
    double mean = 0, scale = 0;
    for (int i : idx) { mean += xs[i]; scale += std::fabs(xs[i]); }
    mean /= idx.size(); scale /= idx.size();
    if (scale <= 0.0) return true;
    double var = 0;
    for (int i : idx) var += (xs[i] - mean) * (xs[i] - mean);
    var /= idx.size();
    return std::sqrt(var) / scale < kMinRelativeSpread;
}

inline bool leastSquares(const std::vector<double>& xs, const std::vector<double>& ys,
                         const std::vector<int>& idx, double& a, double& b) {
    double sx = 0, sy = 0, sxx = 0, sxy = 0;
    for (int i : idx) { sx += xs[i]; sy += ys[i]; sxx += xs[i] * xs[i]; sxy += xs[i] * ys[i]; }
    const double m = (double)idx.size();
    const double denom = m * sxx - sx * sx;
    if (denom <= 0.0 || !std::isfinite(denom)) return false;
    a = (m * sxy - sx * sy) / denom;
    b = (sy * sxx - sx * sxy) / denom;
    return true;
}

}  // namespace depthfit_detail

/**
 * Robust fit of `invDepth = a·oneOverZ + b`. Non-finite pairs are skipped. Returns `ok == false`
 * when fewer than [minSamples] samples remain (before or after trimming), the spread is degenerate,
 * or the slope is not physically sane (a > 0).
 */
inline DepthFit fitDepthScale(const std::vector<float>& oneOverZ, const std::vector<float>& invDepth,
                              int minSamples = 6) {
    using namespace depthfit_detail;
    DepthFit out;
    if (oneOverZ.size() != invDepth.size() || minSamples < 2) return out;
    std::vector<double> xs, ys;
    xs.reserve(oneOverZ.size()); ys.reserve(oneOverZ.size());
    for (size_t i = 0; i < oneOverZ.size(); ++i) {
        if (!std::isfinite(oneOverZ[i]) || !std::isfinite(invDepth[i])) continue;
        xs.push_back(oneOverZ[i]); ys.push_back(invDepth[i]);
    }
    const int n = (int)xs.size();
    if (n < minSamples) return out;
    std::vector<int> all(n);
    for (int i = 0; i < n; ++i) all[i] = i;
    if (degenerate(xs, all)) return out;

    // 1. Least-median-of-squares seed over a deterministic set of sample pairs.
    bool haveSeed = false;
    double la = 0, lb = 0, bestMedian = INFINITY;
    const long long totalPairs = (long long)n * (n - 1) / 2;
    const long long stride = std::max(1LL, totalPairs / kMaxSeedPairs);
    long long k = 0;
    std::vector<double> sq(n);
    for (int i = 0; i < n; ++i) for (int j = i + 1; j < n; ++j) {
        if (k++ % stride != 0) continue;
        const double dx = xs[j] - xs[i];
        if (std::fabs(dx) < 1e-12) continue;
        const double a = (ys[j] - ys[i]) / dx;
        const double b = ys[i] - a * xs[i];
        for (int t = 0; t < n; ++t) { const double r = ys[t] - (a * xs[t] + b); sq[t] = r * r; }
        const double med = median(sq);
        if (med < bestMedian) { bestMedian = med; la = a; lb = b; haveSeed = true; }
    }
    if (!haveSeed && !leastSquares(xs, ys, all, la, lb)) return out;

    // 2. Iterative trimmed least squares around the seed.
    std::vector<int> active = all;
    for (int it = 0; it < kMaxTrimIterations; ++it) {
        std::vector<double> res(n), absActive;
        for (int t = 0; t < n; ++t) res[t] = ys[t] - (la * xs[t] + lb);
        absActive.reserve(active.size());
        double yScale = 0;
        for (int t : active) { absActive.push_back(std::fabs(res[t])); yScale = std::max(yScale, std::fabs(ys[t])); }
        const double sigma = 1.4826 * median(absActive);
        const double cutoff = std::max(kOutlierSigmas * sigma, 1e-6 * std::max(yScale, 1e-12));
        std::vector<int> next;
        for (int t = 0; t < n; ++t) if (std::fabs(res[t]) <= cutoff) next.push_back(t);
        if ((int)next.size() < minSamples || degenerate(xs, next)) return out;
        if (!leastSquares(xs, ys, next, la, lb)) return out;
        const bool converged = (next == active);
        active.swap(next);
        if (converged) break;
    }

    const float a = (float)la, b = (float)lb;
    if (!(a > 1e-6f) || !std::isfinite(a) || !std::isfinite(b)) return out;
    out.a = a; out.b = b; out.ok = true;
    return out;
}

}  // namespace graffitixr
