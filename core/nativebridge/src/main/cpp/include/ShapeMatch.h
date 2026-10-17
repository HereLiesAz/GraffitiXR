#pragma once
#include <cstdint>
#include <algorithm>
#include <vector>
#include "PassEvidence.h"

/**
 * Shape matching for the change pass (docs/PASSES.md, pass B). Colour is not asked about: the pass
 * sees WHICH cells of the wall have changed since the clean-wall photo, and asks whether the changed
 * region has the shape of the design. A region that does is paint, whatever colour it was painted.
 * One that does not is a shadow, a hand, a person walking past.
 *
 * What paint does that those do not: it fills design patches. Looking at one connected changed
 * region and the design patches it touches, each patch is one of
 *   - complete: nearly all of the patch's cells (those that can be seen) have changed;
 *   - untouched: next to none have;
 *   - partial: in between, which is paint in progress.
 * Painting leaves at most a couple of patches partial at a time (the one being worked on), or in a
 * design cut into many small patches, only the ones along the edge of what is done, so most of the
 * region is complete patches; and it keeps almost all of itself inside the design. A shadow or a hand
 * cuts across many patches at once, leaving most of the region in partial ones, and spills outside
 * the design.
 */
namespace shapematch {

/**
 * Connected components (4-neighbour) over a cols x rows grid of [key]s. Cells with equal, non-negative
 * keys that touch share a label; cells with a negative key get -1. Labels are 0, 1, 2, ... in
 * row-major order of first appearance.
 */
inline std::vector<int> label(const std::vector<int>& key, int cols, int rows) {
    std::vector<int> out((size_t)cols * rows, -1);
    if (cols <= 0 || rows <= 0 || key.size() != out.size()) return out;
    int next = 0;
    std::vector<int> stack;
    for (int start = 0; start < cols * rows; ++start) {
        if (key[start] < 0 || out[start] >= 0) continue;
        out[start] = next;
        stack.assign(1, start);
        while (!stack.empty()) {
            const int c = stack.back(); stack.pop_back();
            const int x = c % cols, y = c / cols;
            const int nb[4] = {x > 0 ? c - 1 : -1, x + 1 < cols ? c + 1 : -1,
                               y > 0 ? c - cols : -1, y + 1 < rows ? c + cols : -1};
            for (int n : nb) {
                if (n < 0 || out[n] >= 0 || key[n] != key[c]) continue;
                out[n] = next;
                stack.push_back(n);
            }
        }
        ++next;
    }
    return out;
}

/** A patch with at least this share of its seen cells changed is complete. */
constexpr float kComplete = 0.8f;
/** A patch with at most this share changed is untouched (noise, not paint). */
constexpr float kUntouched = 0.1f;
/** Paint in progress leaves at most this many patches partial... */
constexpr int kMaxPartial = 2;
/** ...or, in a design cut into many small patches, at most this share of the region in partial ones. */
constexpr float kMaxPartialShare = 0.4f;
/** At most this share of a region's cells may lie outside the design. */
constexpr float kMaxOutside = 0.25f;
/** A region smaller than this is noise, unless every patch it touches is itself that small. */
constexpr int kMinRegionCells = 2;

/**
 * One verdict per cell.
 *  - [judged]: the cell could be compared against the clean wall this tick (0 = no data).
 *  - [changed]: the cell differs from the clean wall (only read where judged).
 *  - [patch]: the design patch each cell belongs to (see [label]), -1 outside the design.
 * Each connected region of judged, changed cells is judged as described above. A region that passes
 * marks its cells that lie in a complete or partial patch present. A region that fails, every
 * unchanged judged cell, and every changed cell in an untouched patch are absent.
 */
inline std::vector<uint8_t> verdicts(const std::vector<uint8_t>& changed, const std::vector<uint8_t>& judged,
                                     const std::vector<int>& patch, int cols, int rows) {
    const size_t n = (size_t)cols * rows;
    std::vector<uint8_t> out(n, passes::kNoData);
    if (cols <= 0 || rows <= 0 || changed.size() != n || judged.size() != n || patch.size() != n) return out;
    std::vector<int> key(n, -1);
    for (size_t i = 0; i < n; ++i) {
        if (!judged[i]) continue;
        out[i] = passes::kAbsent;
        if (changed[i]) key[i] = 0;
    }
    const std::vector<int> region = label(key, cols, rows);
    int regions = 0, patches = 0;
    for (size_t i = 0; i < n; ++i) { regions = std::max(regions, region[i] + 1); patches = std::max(patches, patch[i] + 1); }
    // How many cells of each patch can be seen this tick.
    std::vector<int> seen((size_t)patches, 0);
    for (size_t i = 0; i < n; ++i) if (judged[i] && patch[i] >= 0) ++seen[(size_t)patch[i]];

    for (int r = 0; r < regions; ++r) {
        std::vector<int> cells;
        std::vector<int> perPatch((size_t)patches, 0);
        int outside = 0;
        for (size_t i = 0; i < n; ++i) {
            if (region[i] != r) continue;
            cells.push_back((int)i);
            if (patch[i] >= 0) ++perPatch[(size_t)patch[i]]; else ++outside;
        }
        int partial = 0, partialCells = 0;
        bool anyPaint = false, allTiny = true;
        std::vector<char> counts((size_t)patches, 0);   // 1 = complete or partial: its cells are paint
        for (int p = 0; p < patches; ++p) {
            if (perPatch[(size_t)p] == 0 || seen[(size_t)p] == 0) continue;
            const float share = (float)perPatch[(size_t)p] / (float)seen[(size_t)p];
            if (seen[(size_t)p] >= kMinRegionCells) allTiny = false;
            if (share <= kUntouched) continue;
            counts[(size_t)p] = 1;
            anyPaint = true;
            if (share < kComplete) { ++partial; partialCells += perPatch[(size_t)p]; }
        }
        const bool big = (int)cells.size() >= kMinRegionCells || allTiny;
        const bool contained = (float)outside <= kMaxOutside * (float)cells.size();
        const bool shaped = partial <= kMaxPartial || (float)partialCells <= kMaxPartialShare * (float)cells.size();
        if (!anyPaint || !big || !contained || !shaped) continue;
        for (int c : cells) if (patch[(size_t)c] >= 0 && counts[(size_t)patch[(size_t)c]]) out[(size_t)c] = passes::kPresent;
    }
    return out;
}

} // namespace shapematch
