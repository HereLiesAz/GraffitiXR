#ifndef GRAFFITIXR_KEYPOINT_GRID_H
#define GRAFFITIXR_KEYPOINT_GRID_H

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>
#include <opencv2/core.hpp>

/**
 * IMPLEMENTATION.md 4.5 — transliteration of
 * `feature/ar/.../anchor/KeypointGrid.kt`.
 *
 * The Kotlin file is the reference and carries the rationale; `KeypointGridTest` holds it to a
 * brute-force equivalence check across radii both below and above the cell size. This file must
 * reproduce that behaviour exactly, and the two errors it is most likely to reintroduce are called
 * out inline below because both produce a plausible, slightly-wrong answer rather than a crash:
 * fewer corroborated features and nothing else.
 *
 * ## The contract
 *
 * [candidatesWithin] guarantees only one direction: **every** keypoint within `radius` of the query
 * is returned. It may return extras — the swept cells cover a rectangle, not a circle — and the
 * caller applies whatever exact test it needs. The grid exists to make the candidate set small; the
 * ratio test that follows is what decides matches.
 */
class KeypointGrid {
public:
    KeypointGrid() = default;

    /**
     * Bucket the keypoints.
     *
     * @param cellSize edge length of a bucket in pixels — pass the search radius. Floored at 1 px
     *   only to keep it a valid, non-zero divisor below; that floor bounds the CELL size, not the
     *   grid's cols*rows, which is also driven by the keypoints' spatial extent (maxX-minX,
     *   maxY-minY) — an unbounded extent with cellSize pinned at its 1px floor still produces an
     *   unbounded grid. The one current caller (MobileGS.cpp) floors its radius well above 1px
     *   before calling this, which is what actually keeps mCols*mRows bounded today; a future
     *   caller passing a very small cellSize against a very large keypoint spread would not be.
     *   Capped defensively below regardless.
     */
    void build(const std::vector<cv::KeyPoint>& kps, float cellSize) {
        mCell = (std::isfinite(cellSize) && cellSize >= 1.0f) ? cellSize : 1.0f;
        mCols = 0; mRows = 0; mMinX = 0.0f; mMinY = 0.0f;
        mCells.clear();
        if (kps.empty()) return;

        float minX = std::numeric_limits<float>::max();
        float minY = std::numeric_limits<float>::max();
        float maxX = -std::numeric_limits<float>::max();
        float maxY = -std::numeric_limits<float>::max();
        for (const auto& kp : kps) {
            // A non-finite keypoint would poison the bounds and collapse the whole grid to one cell,
            // quietly turning the local search back into a global one.
            if (!std::isfinite(kp.pt.x) || !std::isfinite(kp.pt.y)) continue;
            if (kp.pt.x < minX) minX = kp.pt.x;
            if (kp.pt.x > maxX) maxX = kp.pt.x;
            if (kp.pt.y < minY) minY = kp.pt.y;
            if (kp.pt.y > maxY) maxY = kp.pt.y;
        }
        if (minX > maxX) return;   // every keypoint was non-finite

        mMinX = minX; mMinY = minY;
        mCols = std::max(1, (int)((maxX - minX) / mCell) + 1);
        mRows = std::max(1, (int)((maxY - minY) / mCell) + 1);
        // Defensive cap independent of the cellSize floor above -- see this method's doc. A cell
        // count in the low millions is already far more than any real keypoint distribution
        // needs; this only ever engages for a degenerate (huge-spread, tiny-cell) combination.
        static constexpr int kMaxCellCount = 4'000'000;
        if ((int64_t)mCols * (int64_t)mRows > kMaxCellCount) {
            const double scale = std::sqrt((double)kMaxCellCount / ((double)mCols * (double)mRows));
            mCols = std::max(1, (int)(mCols * scale));
            mRows = std::max(1, (int)(mRows * scale));
            mCell = std::max(mCell, (maxX - minX) / (float)mCols);
        }
        mCells.assign((size_t)mCols * (size_t)mRows, {});
        for (int i = 0; i < (int)kps.size(); ++i) {
            const auto& p = kps[(size_t)i].pt;
            if (!std::isfinite(p.x) || !std::isfinite(p.y)) continue;
            const int c = clampi((int)((p.x - mMinX) / mCell), 0, mCols - 1);
            const int r = clampi((int)((p.y - mMinY) / mCell), 0, mRows - 1);
            mCells[(size_t)r * (size_t)mCols + (size_t)c].push_back(i);
        }
    }

    /**
     * Indices of every keypoint whose cell could hold a point within [radius] of (x, y).
     *
     * Sweeps the cell RANGE covering the query's bounding box, not a fixed 3x3. Those coincide only
     * when cellSize >= radius; the caller may query one grid at several radii (the drift terms move
     * the radius frame to frame), and a hard-coded 3x3 would then silently miss candidates.
     */
    void candidatesWithin(float x, float y, float radius, std::vector<int>& out) const {
        out.clear();
        if (mCols == 0 || mRows == 0) return;
        if (radius < 0.0f || !std::isfinite(radius)) return;
        if (!std::isfinite(x) || !std::isfinite(y)) return;

        // Computed UNCLAMPED first, so a query whose range lies entirely outside the grid returns
        // nothing. Clamping straight into [0, count) — the obvious way to write this — silently maps
        // a distant query onto the edge cells and hands back their contents, which on a grid one
        // cell wide (few or tightly clustered keypoints) means every keypoint in the frame for a
        // query nowhere near any of them.
        const int c0raw = cellFloor(x - radius, mMinX);
        const int c1raw = cellFloor(x + radius, mMinX);
        const int r0raw = cellFloor(y - radius, mMinY);
        const int r1raw = cellFloor(y + radius, mMinY);
        if (c1raw < 0 || c0raw > mCols - 1 || r1raw < 0 || r0raw > mRows - 1) return;

        const int c0 = clampi(c0raw, 0, mCols - 1);
        const int c1 = clampi(c1raw, 0, mCols - 1);
        const int r0 = clampi(r0raw, 0, mRows - 1);
        const int r1 = clampi(r1raw, 0, mRows - 1);
        for (int r = r0; r <= r1; ++r) {
            const size_t base = (size_t)r * (size_t)mCols;
            for (int c = c0; c <= c1; ++c) {
                const auto& bucket = mCells[base + (size_t)c];
                out.insert(out.end(), bucket.begin(), bucket.end());
            }
        }
    }

private:
    /**
     * Unclamped cell index, which may be negative or past the last cell. std::floor, NOT a plain
     * int cast: truncation goes toward zero, so a query half a cell to the LEFT of the origin would
     * land in cell 0 instead of -1 and the out-of-range test above would never fire on that side.
     */
    int cellFloor(float v, float minV) const {
        if (!std::isfinite(v)) return v > 0.0f ? INT32_MAX : INT32_MIN;
        // FLOAT division, matching the Kotlin reference's `(v - min) / cellSize` on Float operands.
        // Promoting to double here computes a more accurate quotient, which sounds like an
        // improvement and is a divergence: at a boundary where the float quotient rounds up to
        // exactly an integer and the double quotient does not, the two implementations return
        // different cells and the out-of-range reject fires on one side and not the other. The
        // reference is the specification; being more precise than it is still being different from
        // it. Widened to double only for the floor and the range clamp, where no such disagreement
        // is possible.
        const float q = (v - minV) / mCell;
        const double idx = std::floor((double)q);
        if (idx > (double)INT32_MAX) return INT32_MAX;
        if (idx < (double)INT32_MIN) return INT32_MIN;
        return (int)idx;
    }

    static int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    float mCell = 1.0f;
    float mMinX = 0.0f;
    float mMinY = 0.0f;
    int mCols = 0;
    int mRows = 0;
    std::vector<std::vector<int>> mCells;
};

#endif  // GRAFFITIXR_KEYPOINT_GRID_H
