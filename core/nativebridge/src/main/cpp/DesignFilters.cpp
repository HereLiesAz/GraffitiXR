#include "include/DesignFilters.h"
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>
#include <algorithm>

namespace designfilters {

namespace {

cv::Mat toBgr(const cv::Mat& m) {
    cv::Mat out;
    if (m.channels() == 4)      cv::cvtColor(m, out, cv::COLOR_RGBA2BGR);
    else if (m.channels() == 3) cv::cvtColor(m, out, cv::COLOR_RGB2BGR);
    else                        cv::cvtColor(m, out, cv::COLOR_GRAY2BGR);
    return out;
}

// Equal-width tone bands over [0,255], levels evenly spread: n=2 -> {64,191}, n=4 -> {32,96,160,224}.
cv::Mat quantise(const cv::Mat& gray, int n) {
    cv::Mat out(gray.size(), CV_8U);
    const int band = 256 / n;
    for (int y = 0; y < gray.rows; ++y) {
        const uchar* s = gray.ptr<uchar>(y);
        uchar* d = out.ptr<uchar>(y);
        for (int x = 0; x < gray.cols; ++x) d[x] = (uchar)(std::min(n - 1, s[x] / band) * band + band / 2);
    }
    return out;
}

} // namespace

cv::Mat stencilLayers(const cv::Mat& design, int tones, float influence) {
    if (design.empty() || design.depth() != CV_8U || tones < 2 || tones > 8) return cv::Mat();
    const cv::Mat bgr = toBgr(design);
    const double down = std::min(1.0, 640.0 / std::max(bgr.cols, bgr.rows));
    cv::Mat small;
    if (down < 1.0) cv::resize(bgr, small, cv::Size(), down, down, cv::INTER_AREA); else small = bgr;

    // V = max(R,G,B), as HSV's value channel.
    cv::Mat v(small.size(), CV_8UC1);
    for (int y = 0; y < small.rows; ++y) {
        const cv::Vec3b* s = small.ptr<cv::Vec3b>(y);
        uchar* d = v.ptr<uchar>(y);
        for (int x = 0; x < small.cols; ++x) d[x] = std::max(s[x][0], std::max(s[x][1], s[x][2]));
    }
    // Detail simplification (the old tool's, at its default influence 0.5 -> 9) and tonal bias.
    int ksize = 15 - (int)(influence * 14);
    if (ksize % 2 == 0) ++ksize;
    if (ksize > 1) cv::medianBlur(v, v, ksize);
    v.convertTo(v, -1, 1.0, (influence - 0.5) * 80.0);

    cv::Mat samples;
    v.reshape(1, (int)v.total()).convertTo(samples, CV_32F);
    if ((int)samples.rows < tones) return cv::Mat();
    cv::Mat labels, centers;
    cv::theRNG().state = 0x57e4c11ULL;   // the same design always yields the same layers
    cv::kmeans(samples, tones, labels,
               cv::TermCriteria(cv::TermCriteria::EPS + cv::TermCriteria::COUNT, 10, 1.0),
               3, cv::KMEANS_PP_CENTERS, centers);

    // Groups in ascending order of their centre; group g becomes tone (g + 0.5) / tones.
    std::vector<int> order(tones);
    for (int i = 0; i < tones; ++i) order[i] = i;
    std::sort(order.begin(), order.end(), [&](int a, int b) { return centers.at<float>(a) < centers.at<float>(b); });
    std::vector<int> rank(tones);
    for (int r = 0; r < tones; ++r) rank[order[r]] = r;

    cv::Mat out(small.size(), CV_8UC1, cv::Scalar((int)(255.0 * 0.5 / tones)));
    const cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(5, 5));
    for (int r = 1; r < tones; ++r) {
        // Each layer is the pixels at its tone or above (sheets are laid bottom to top), closed.
        cv::Mat mask(small.size(), CV_8UC1);
        for (int i = 0; i < (int)small.total(); ++i) mask.data[i] = rank[labels.at<int>(i)] >= r ? 255 : 0;
        cv::morphologyEx(mask, mask, cv::MORPH_CLOSE, kernel);
        out.setTo((int)(255.0 * (r + 0.5) / tones), mask);
    }
    if (down < 1.0) cv::resize(out, out, bgr.size(), 0, 0, cv::INTER_NEAREST);
    return out;
}

cv::Mat applyUnchecked(int id, const cv::Mat& design) {
    if (design.empty() || design.depth() != CV_8U || design.cols < 16 || design.rows < 16) return cv::Mat();
    if (id < 0 || id >= kCount) return cv::Mat();
    const cv::Mat bgr = toBgr(design);
    cv::Mat gray;
    cv::cvtColor(bgr, gray, cv::COLOR_BGR2GRAY);
    const int minSide = std::min(gray.cols, gray.rows);

    switch (id) {
    case kSketch: {
        cv::Mat inv, blur;
        cv::bitwise_not(gray, inv);
        cv::GaussianBlur(inv, blur, cv::Size(0, 0), std::max(2.0, minSide / 100.0));
        cv::Mat out;
        cv::divide(gray, cv::Scalar(255) - blur, out, 256.0);
        return out;
    }
    case kMosaic: {
        const int tile = std::max(8, minSide / 24);
        cv::Mat small, out;
        cv::resize(bgr, small, cv::Size(std::max(1, gray.cols / tile), std::max(1, gray.rows / tile)), 0, 0, cv::INTER_AREA);
        cv::resize(small, out, gray.size(), 0, 0, cv::INTER_NEAREST);
        cv::cvtColor(out, out, cv::COLOR_BGR2GRAY);
        return out;
    }
    case kPosterize2: {
        cv::Mat out;
        cv::threshold(gray, out, 0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);
        return out;
    }
    case kPosterize4:
        return quantise(gray, 4);
    case kCartoon: {
        cv::Mat small, smooth, g, edges;
        const double s = std::min(1.0, 400.0 / std::max(gray.cols, gray.rows));
        cv::resize(bgr, small, cv::Size(), s, s, cv::INTER_AREA);
        for (int i = 0; i < 2; ++i) { cv::bilateralFilter(small, smooth, 7, 40, 7); small = smooth.clone(); }
        cv::cvtColor(small, g, cv::COLOR_BGR2GRAY);
        g = quantise(g, 6);
        cv::Canny(g, edges, 40, 120);
        g.setTo(0, edges);
        cv::resize(g, g, gray.size(), 0, 0, cv::INTER_NEAREST);
        return g;
    }
    case kBlobs: {
        cv::Mat bin;
        cv::threshold(gray, bin, 0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);
        const int k = std::max(3, minSide / 60) | 1;
        const cv::Mat el = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(k, k));
        cv::morphologyEx(bin, bin, cv::MORPH_OPEN, el);
        cv::morphologyEx(bin, bin, cv::MORPH_CLOSE, el);
        return bin;
    }
    case kInvert: {
        cv::Mat out;
        cv::bitwise_not(gray, out);
        return out;
    }
    case kWarmLuma: {
        // BGR weights: red-heavy. Colours plain grey renders alike (a red and a green of equal
        // luminance) come out as different tones here.
        const cv::Matx13f w(0.10f, 0.30f, 0.60f);
        cv::Mat out;
        cv::transform(bgr, out, w);
        out.convertTo(out, CV_8U);
        return out;
    }
    case kStencil2: return stencilLayers(design, 2);
    case kStencil3: return stencilLayers(design, 3);
    case kStencil4: return stencilLayers(design, 4);
    case kStencil5: return stencilLayers(design, 5);
    default: break;
    }
    return cv::Mat();
}

cv::Mat apply(int id, const cv::Mat& design) {
    // A filter is only a hint about where to look: whatever goes wrong inside OpenCV, the design
    // registration it rides on must not.
    try { return applyUnchecked(id, design); } catch (const cv::Exception&) { return cv::Mat(); }
}

std::vector<cv::Point2f> locations(const cv::Mat& filtered, int maxPoints, int border) {
    std::vector<cv::Point2f> pts;
    if (filtered.empty() || filtered.type() != CV_8UC1 || maxPoints <= 0) return pts;
    if (filtered.cols <= 2 * border || filtered.rows <= 2 * border) return pts;
    // FAST corners, strongest first, thinned so no two are closer than minDist. (Not
    // goodFeaturesToTrack: OpenCV 5 moved it out of imgproc, and the Android build is OpenCV 5.)
    const int minDist = (int)std::max(8.0, std::min(filtered.cols, filtered.rows) / 40.0);
    std::vector<cv::KeyPoint> kps;
    cv::FastFeatureDetector::create(20, true)->detect(filtered, kps);
    std::sort(kps.begin(), kps.end(), [](const cv::KeyPoint& a, const cv::KeyPoint& b) { return a.response > b.response; });
    const int gw = filtered.cols / minDist + 1, gh = filtered.rows / minDist + 1;
    std::vector<char> taken((size_t)gw * gh, 0);
    for (const auto& k : kps) {
        const cv::Point2f p = k.pt;
        if (p.x < border || p.y < border || p.x >= filtered.cols - border || p.y >= filtered.rows - border) continue;
        const int cx = (int)p.x / minDist, cy = (int)p.y / minDist;
        bool near = false;
        for (int dy = -1; dy <= 1 && !near; ++dy) for (int dx = -1; dx <= 1 && !near; ++dx) {
            const int nx = cx + dx, ny = cy + dy;
            if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;
            if (taken[(size_t)ny * gw + nx]) {
                // a point recorded in that cell is within minDist only if actually close; recheck below
                for (const auto& q : pts) {
                    const float ex = q.x - p.x, ey = q.y - p.y;
                    if (ex * ex + ey * ey < (float)minDist * minDist) { near = true; break; }
                }
            }
        }
        if (near) continue;
        taken[(size_t)cy * gw + cx] = 1;
        pts.push_back(p);
        if ((int)pts.size() >= maxPoints) break;
    }
    return pts;
}

} // namespace designfilters
