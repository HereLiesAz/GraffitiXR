#pragma once
#include <cstdint>

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
    kPassCount = 2,
};

inline const char* passName(int id) {
    switch (id) {
        case kPassFeatures: return "features";
        case kPassColour:   return "colour";
        default:            return "unknown";
    }
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
