#pragma once
#include <opencv2/core.hpp>
#include <vector>

/**
 * The design filter bank (docs/PASSES.md). Each filter turns the DESIGN into a different picture of
 * itself -- a sketch, a mosaic, two or four tones, a cartoon, blob silhouettes, its negative, a
 * colour-weighted grey -- offscreen, and only so the detector finds a different set of places worth
 * looking at. The filtered picture is never matched against anything. Descriptors for those places
 * come from the unfiltered design, and are matched against the raw live frame's own features.
 *
 * Nothing here ever sees the live frame.
 */
namespace designfilters {

enum Id : int {
    kSketch = 0,      //!< colour-dodge line drawing: edges and hatching, no fills
    kMosaic,          //!< coarse tiles: only patch-scale structure survives
    kPosterize2,      //!< two tones around the design's own median
    kPosterize4,      //!< four tones
    kCartoon,         //!< smoothed flats with their outlines
    kBlobs,           //!< filled silhouettes of the colour patches, small specks removed
    kInvert,          //!< the negative
    kWarmLuma,        //!< red-weighted luminance: separates what plain grey merges
    kCount
};

inline const char* name(int id) {
    switch (id) {
        case kSketch:     return "sketch";
        case kMosaic:     return "mosaic";
        case kPosterize2: return "posterize2";
        case kPosterize4: return "posterize4";
        case kCartoon:    return "cartoon";
        case kBlobs:      return "blobs";
        case kInvert:     return "invert";
        case kWarmLuma:   return "warm-luma";
        default:          return "unknown";
    }
}

/**
 * Applies filter [id] to the design [design] (1, 3 or 4 channels, 8-bit; alpha, when present, is
 * ignored) and returns a single-channel 8-bit picture of the same size. Empty on bad input.
 */
cv::Mat apply(int id, const cv::Mat& design);

/**
 * Where to look: up to [maxPoints] well-separated corner-like places in [filtered], at least
 * [border] pixels inside the picture (descriptors need a neighbourhood).
 */
std::vector<cv::Point2f> locations(const cv::Mat& filtered, int maxPoints, int border = 32);

} // namespace designfilters
