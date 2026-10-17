#pragma once
#include <cmath>
#include <cstdint>
#include "DesignFilters.h"

/**
 * Passes: independent ways of deciding that a part of the design has been painted.
 *
 * A pass looks for a different set of things in the SAME raw camera frame (the live frame is never
 * filtered or rewritten) and says, per design cell, whether it sees paint there. Passes run all the
 * time and their votes meet in [combineVotes]; the cell is judged by that one function, so how the
 * passes are weighed can change in one place.
 *
 * Today's two evidence sources are the first two passes: feature confirmations, and the hue-free
 * colour relationship against the bare-wall photo. See docs/PASSES.md.
 */
namespace passes {

/** One pass's verdict on one cell for one tick. */
enum Verdict : uint8_t {
    kNoData = 0,   //!< the pass could not judge this cell this tick (out of view, no reference, ...)
    kAbsent = 1,   //!< the pass looked and does not see paint
    kPresent = 2,  //!< the pass looked and sees paint
};

/** Stable pass identities: index into the stats table and the diagnostics. */
enum PassId : int {
    kPassFeatures = 0,   //!< design features confirmed on the wall (descriptor corroboration)
    kPassColour = 1,     //!< left the bare wall and matches the paint its design colour was painted with
    kPassFilterBase = 2,   //!< pass kPassFilterBase + f looks where design filter f (DesignFilters.h) points
    kPassChange = kPassFilterBase + designfilters::kCount,   //!< changed since the clean-wall photo, in the shape of a design patch
    kPassCount = kPassChange + 1,
};

inline const char* passName(int id) {
    switch (id) {
        case kPassFeatures: return "features";
        case kPassColour:   return "colour";
        case kPassChange:   return "change";
        default:
            if (id >= kPassFilterBase && id < kPassCount) return designfilters::name(id - kPassFilterBase);
            return "unknown";
    }
}

/**
 * A colour for pass [id] in the CV preview and its legend, packed 0xRRGGBB. Hues are spread by the
 * golden angle so neighbouring ids are far apart; the one palette serves the native drawing and the
 * legend the app shows beside it.
 */
inline uint32_t passRgb(int id) {
    const float h = std::fmod((float)id * 137.508f, 360.0f) / 60.0f;
    const float x = 1.0f - std::fabs(std::fmod(h, 2.0f) - 1.0f);
    float r = 0, g = 0, b = 0;
    switch ((int)h) {
        case 0: r = 1; g = x; break;
        case 1: r = x; g = 1; break;
        case 2: g = 1; b = x; break;
        case 3: g = x; b = 1; break;
        case 4: r = x; b = 1; break;
        default: r = 1; b = x; break;
    }
    // Saturated but not pure, so the dots read on both light and dark walls.
    auto q = [](float v) { return (uint32_t)std::lround(60.0f + 195.0f * v); };
    return (q(r) << 16) | (q(g) << 8) | q(b);
}

/**
 * Whether the votes confirm paint on a cell this tick. A cell is confirmed when ANY pass sees paint:
 * the passes fail differently, so one dissenting pass must not veto another's evidence. (The
 * consecutive-ticks rule that turns a confirmation into a painted cell stays with the caller.)
 */
inline bool combineVotes(const uint8_t* votes, int n) {
    for (int i = 0; i < n; ++i) if (votes[i] == kPresent) return true;
    return false;
}

/** Running numbers for one pass, for diagnostics. */
struct Stats {
    long ticks = 0;          //!< ticks on which the pass judged at least one cell
    long observed = 0;       //!< cell-verdicts that were not kNoData, summed over ticks
    long present = 0;        //!< cell-verdicts that were kPresent, summed over ticks
    long lastObserved = 0;   //!< cells judged on the latest tick
    long lastPresent = 0;    //!< of which kPresent
};

} // namespace passes
