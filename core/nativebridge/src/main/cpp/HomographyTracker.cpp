#include "HomographyTracker.h"
#include <android/log.h>
#include <cmath>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "HomographyTracker", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "HomographyTracker", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "HomographyTracker", __VA_ARGS__)

namespace {

cv::Mat toGray(const cv::Mat& rgbaOrRgb) {
    cv::Mat gray;
    if (rgbaOrRgb.channels() == 4) {
        cv::cvtColor(rgbaOrRgb, gray, cv::COLOR_RGBA2GRAY);
    } else if (rgbaOrRgb.channels() == 3) {
        cv::cvtColor(rgbaOrRgb, gray, cv::COLOR_RGB2GRAY);
    } else {
        gray = rgbaOrRgb;
    }
    return gray;
}

} // namespace

HomographyTracker::HomographyTracker() {
    // Mirrors MobileGS's ORB config (1500 features) so the two engines behave comparably on the
    // same device; BruteForce-Hamming is the correct matcher for ORB's binary descriptors.
    mDetector = cv::ORB::create(1500);
    mMatcher = cv::DescriptorMatcher::create("BruteForce-Hamming");
}

bool HomographyTracker::setReference(const cv::Mat& referenceRgba, float objectHalfW, float objectHalfH) {
    std::lock_guard<std::mutex> lock(mMutex);
    mHasReference = false;
    mRefKeypoints.clear();
    mRefDescriptors.release();
    mRefObjectPoints.clear();

    if (referenceRgba.empty() || !(objectHalfW > 0.0f) || !(objectHalfH > 0.0f)) {
        LOGW("setReference: empty image or non-positive half-extents");
        return false;
    }

    cv::Mat gray = toGray(referenceRgba);
    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;
    mDetector->detectAndCompute(gray, cv::noArray(), kps, descs);

    if ((int)kps.size() < kMinReferenceFeatures || descs.empty()) {
        LOGW("setReference: only %zu features (need %d) — reference too plain/blurry",
             kps.size(), kMinReferenceFeatures);
        return false;
    }

    // Pixel -> plane mapping, computed once here so track() reuses it every frame (class doc's
    // scale section): top-left pixel -> (-objectHalfW, +objectHalfH); +X right, +Y up, Z=0.
    const float w = (float)gray.cols;
    const float h = (float)gray.rows;
    std::vector<cv::Point3f> objPts;
    objPts.reserve(kps.size());
    for (const auto& kp : kps) {
        float u = kp.pt.x / w;
        float v = kp.pt.y / h;
        objPts.emplace_back((u - 0.5f) * 2.0f * objectHalfW, -(v - 0.5f) * 2.0f * objectHalfH, 0.0f);
    }

    mRefKeypoints = std::move(kps);
    mRefDescriptors = descs;
    mRefObjectPoints = std::move(objPts);
    mHasReference = true;
    LOGD("setReference: stored %zu features, half-extents (%.3f, %.3f)",
         mRefKeypoints.size(), objectHalfW, objectHalfH);
    return true;
}

bool HomographyTracker::hasReference() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return mHasReference;
}

void HomographyTracker::reset() {
    std::lock_guard<std::mutex> lock(mMutex);
    mHasReference = false;
    mRefKeypoints.clear();
    mRefDescriptors.release();
    mRefObjectPoints.clear();
}

bool HomographyTracker::track(const cv::Mat& frameRgba, float fx, float fy, float cx, float cy,
                               float* outPoseMat16, float* outConfidence) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mHasReference || frameRgba.empty()) return false;

    cv::Mat gray = toGray(frameRgba);
    std::vector<cv::KeyPoint> frameKps;
    cv::Mat frameDescs;
    mDetector->detectAndCompute(gray, cv::noArray(), frameKps, frameDescs);
    if (frameDescs.empty()) return false;

    std::vector<std::vector<cv::DMatch>> knn;
    mMatcher->knnMatch(mRefDescriptors, frameDescs, knn, 2);

    std::vector<cv::Point3f> objPts;
    std::vector<cv::Point2f> imgPts;
    for (auto& m : knn) {
        if (m.size() < 2) continue;
        if (m[0].distance < kLoweRatio * m[1].distance) {
            objPts.push_back(mRefObjectPoints[m[0].queryIdx]);
            imgPts.push_back(frameKps[m[0].trainIdx].pt);
        }
    }
    if ((int)objPts.size() < kMinMatches) {
        LOGD("track: only %zu Lowe-ratio matches (need %d)", objPts.size(), kMinMatches);
        return false;
    }

    cv::Mat K = (cv::Mat_<double>(3, 3) << fx, 0, cx, 0, fy, cy, 0, 0, 1);
    cv::Mat rvec, tvec;
    std::vector<int> inliers;
    // Same call shape as MobileGS::runRelocPass's PnP solve, for this class's one-plane case.
    if (!cv::solvePnPRansac(objPts, imgPts, K, cv::Mat(), rvec, tvec, false, 100,
                             kRansacReprojThresholdPx, 0.99, inliers)) {
        LOGD("track: solvePnPRansac failed to converge");
        return false;
    }
    if ((int)inliers.size() < kMinInliers) {
        LOGD("track: only %zu RANSAC inliers (need %d)", inliers.size(), kMinInliers);
        return false;
    }
    float ratio = (float)inliers.size() / (float)objPts.size();
    if (ratio < kMinInlierRatio) {
        LOGD("track: inlier ratio %.2f below threshold", ratio);
        return false;
    }

    // IPPE-refine on the inlier set: a purely planar target admits a "flip" ambiguity plain PnP
    // does not resolve on its own. IPPE enumerates the flip candidates explicitly; keep whichever
    // pose (RANSAC's own, or an IPPE candidate) reprojects the inliers best. Never makes the pose
    // worse — a failed/degenerate IPPE solve (caught below) just keeps the RANSAC pose.
    {
        std::vector<cv::Point3f> inObj; std::vector<cv::Point2f> inImg;
        inObj.reserve(inliers.size()); inImg.reserve(inliers.size());
        for (int idx : inliers) { inObj.push_back(objPts[idx]); inImg.push_back(imgPts[idx]); }

        auto reprojError = [&](const cv::Mat& rv, const cv::Mat& tv) {
            std::vector<cv::Point2f> proj;
            cv::projectPoints(inObj, rv, tv, K, cv::Mat(), proj);
            double e = 0;
            for (size_t k = 0; k < proj.size(); ++k) e += cv::norm(proj[k] - inImg[k]);
            return e;
        };
        double bestErr = reprojError(rvec, tvec);
        try {
            std::vector<cv::Mat> rvecs, tvecs;
            int n = cv::solvePnPGeneric(inObj, inImg, K, cv::Mat(), rvecs, tvecs, false, cv::SOLVEPNP_IPPE);
            for (int s = 0; s < n; ++s) {
                double e = reprojError(rvecs[s], tvecs[s]);
                if (e < bestErr) { bestErr = e; rvecs[s].copyTo(rvec); tvecs[s].copyTo(tvec); }
            }
        } catch (const cv::Exception& e) {
            LOGD("track: IPPE refine skipped (%s) — keeping RANSAC pose", e.what());
        }
    }

    cv::Mat Rmat;
    cv::Rodrigues(rvec, Rmat);
    cv::Matx33d Rcv(
        Rmat.at<double>(0, 0), Rmat.at<double>(0, 1), Rmat.at<double>(0, 2),
        Rmat.at<double>(1, 0), Rmat.at<double>(1, 1), Rmat.at<double>(1, 2),
        Rmat.at<double>(2, 0), Rmat.at<double>(2, 1), Rmat.at<double>(2, 2));
    cv::Vec3d tcv(tvec.at<double>(0), tvec.at<double>(1), tvec.at<double>(2));

    // OpenCV (+Z forward, +Y down) -> OpenGL (-Z forward, +Y up), same self-inverse flip
    // MobileGS.cpp uses for its own reloc pose (see computeRectifyHomography): C = diag(1,-1,-1),
    // R_gl = C R_cv C, t_gl = C t_cv. Without this the pose would be upside down and mirrored on
    // screen relative to every other renderer in this app.
    const cv::Matx33d C(1, 0, 0, 0, -1, 0, 0, 0, -1);
    cv::Matx33d Rgl = C * Rcv * C;
    cv::Vec3d tgl = C * tcv;

    // Column-major 4x4: columns are the rotated basis vectors, then translation.
    outPoseMat16[0] = (float)Rgl(0, 0); outPoseMat16[1] = (float)Rgl(1, 0); outPoseMat16[2] = (float)Rgl(2, 0); outPoseMat16[3] = 0.0f;
    outPoseMat16[4] = (float)Rgl(0, 1); outPoseMat16[5] = (float)Rgl(1, 1); outPoseMat16[6] = (float)Rgl(2, 1); outPoseMat16[7] = 0.0f;
    outPoseMat16[8] = (float)Rgl(0, 2); outPoseMat16[9] = (float)Rgl(1, 2); outPoseMat16[10] = (float)Rgl(2, 2); outPoseMat16[11] = 0.0f;
    outPoseMat16[12] = (float)tgl(0); outPoseMat16[13] = (float)tgl(1); outPoseMat16[14] = (float)tgl(2); outPoseMat16[15] = 1.0f;

    *outConfidence = ratio;
    return true;
}
