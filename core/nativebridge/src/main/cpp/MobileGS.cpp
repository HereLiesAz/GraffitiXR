#include "MobileGS.h"
#include <android/log.h>
#include <GLES3/gl3.h>
#include <cmath>
#include <algorithm>
#include <cstring>

#define LOG_TAG "MobileGS"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Tuning Parameters
static const int MAX_MAP_POINTS = 5000;
static const float PRUNE_RADIUS_PX = 20.0f; // Pixels radius to consider "Overpainted"
static const float RATIO_THRESH = 0.75f;
static const int MIN_TARGET_MATCHES_TO_EVOLVE = 8;
static const float DEFAULT_WALL_DEPTH = 1.5f; // Meters (Assumption for monocular initialization)

MobileGS::MobileGS() : mIsInitialized(false), mHasTarget(false) {
    mOrb = cv::ORB::create(1000);
    mMatcher = cv::DescriptorMatcher::create(cv::DescriptorMatcher::BRUTEFORCE_HAMMING);

    // Initialize matrices to identity
    std::fill(mViewMatrix, mViewMatrix + 16, 0.0f);
    std::fill(mProjMatrix, mProjMatrix + 16, 0.0f);
    mViewMatrix[0] = mViewMatrix[5] = mViewMatrix[10] = mViewMatrix[15] = 1.0f;
    mProjMatrix[0] = mProjMatrix[5] = mProjMatrix[10] = mProjMatrix[15] = 1.0f;
}

MobileGS::~MobileGS() {
    Cleanup();
}

void MobileGS::Initialize(int width, int height) {
    mWidth = width;
    mHeight = height;
    mIsInitialized = true;
    LOGD("MobileGS Initialized: %dx%d", width, height);
}

void MobileGS::Cleanup() {
    std::lock_guard<std::mutex> lock(mMapMutex);
    mMapKeypoints.clear();
    mMapPoints3D.clear();
    mMapDescriptors.release();
    mTargetDescriptors.release();
    mIsInitialized = false;
}

void MobileGS::SetTargetDescriptors(const cv::Mat& descriptors) {
    std::lock_guard<std::mutex> lock(mMapMutex);
    descriptors.copyTo(mTargetDescriptors);
    mHasTarget = !mTargetDescriptors.empty();
    LOGD("Target Descriptors Set: %d features. Teleology Active.", mTargetDescriptors.rows);
}

bool MobileGS::IsTrackingTarget() const {
    return mHasTarget;
}

void MobileGS::Update(const cv::Mat& cameraFrame, const float* viewMatrix, const float* projectionMatrix) {
    if (!mIsInitialized || cameraFrame.empty()) return;

    // Cache matrices for projection
    memcpy(mViewMatrix, viewMatrix, 16 * sizeof(float));
    memcpy(mProjMatrix, projectionMatrix, 16 * sizeof(float));

    ProcessFrame(cameraFrame);
}

void MobileGS::ProcessFrame(const cv::Mat& frame) {
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;

    mOrb->detectAndCompute(frame, cv::noArray(), keypoints, descriptors);

    if (descriptors.empty()) return;

    MatchAndFuse(keypoints, descriptors);
}

void MobileGS::MatchAndFuse(const std::vector<cv::KeyPoint>& curKeypoints, const cv::Mat& curDescriptors) {
    std::lock_guard<std::mutex> lock(mMapMutex);

    // Initialization: If map is empty, create initial cloud on a virtual plane
    if (mMapDescriptors.empty()) {
        mMapKeypoints = curKeypoints;
        curDescriptors.copyTo(mMapDescriptors);

        // Promote to 3D: Assume flat wall parallel to initial camera frame
        mMapPoints3D.reserve(curKeypoints.size());
        for (const auto& kp : curKeypoints) {
            mMapPoints3D.push_back(UnprojectPoint(kp, DEFAULT_WALL_DEPTH));
        }
        return;
    }

    // --- 1. TARGET MATCHING (The Evolution Check) ---
    std::vector<bool> isTargetFeature(curKeypoints.size(), false);
    std::vector<int> targetMatchesIndices;

    if (mHasTarget) {
        std::vector<std::vector<cv::DMatch>> target_knn;
        mMatcher->knnMatch(curDescriptors, mTargetDescriptors, target_knn, 2);

        for (size_t i = 0; i < target_knn.size(); i++) {
            if (target_knn[i].size() >= 2 &&
                    target_knn[i][0].distance < RATIO_THRESH * target_knn[i][1].distance) {

                int queryIdx = target_knn[i][0].queryIdx;
                isTargetFeature[queryIdx] = true;
                targetMatchesIndices.push_back(queryIdx);
            }
        }
    }

    // --- 2. MAP MATCHING (Localization) ---
    // Standard matching against existing descriptors
    std::vector<std::vector<cv::DMatch>> map_knn;
    mMatcher->knnMatch(curDescriptors, mMapDescriptors, map_knn, 2);

    std::vector<bool> isMapFeature(curKeypoints.size(), false);
    for (size_t i = 0; i < map_knn.size(); i++) {
        if (map_knn[i].size() >= 2 &&
                map_knn[i][0].distance < RATIO_THRESH * map_knn[i][1].distance) {
            isMapFeature[map_knn[i][0].queryIdx] = true;
        }
    }

    // --- 3. FUSION & PRUNING ---
    bool performEvolution = (targetMatchesIndices.size() > MIN_TARGET_MATCHES_TO_EVOLVE);

    if (performEvolution) {
        std::vector<cv::KeyPoint> pointsToAdd;
        std::vector<cv::Point3f> points3DToAdd;
        cv::Mat descriptorsToAdd;

        // Collect New Points (Matches Target, Not in Map)
        for (int idx : targetMatchesIndices) {
            if (!isMapFeature[idx]) {
                pointsToAdd.push_back(curKeypoints[idx]);
                // Project onto the estimated surface
                // Note: For robust SLAM, we should intersect ray with existing plane model.
                // Here, we use a heuristic depth relative to camera Z.
                points3DToAdd.push_back(UnprojectPoint(curKeypoints[idx], DEFAULT_WALL_DEPTH));
                descriptorsToAdd.push_back(curDescriptors.row(idx));
            }
        }

        // Pruning: Remove old map points that are spatially coincident with new paint
        if (!pointsToAdd.empty()) {
            std::vector<bool> keepMask(mMapPoints3D.size(), true);
            int removeCount = 0;

            // Project current map to 2D for collision check
            std::vector<cv::Point2f> projectedMap(mMapPoints3D.size());
            for(size_t i=0; i<mMapPoints3D.size(); ++i) {
                projectedMap[i] = ProjectPoint(mMapPoints3D[i]);
            }

            for (const auto& newPt : pointsToAdd) {
                for (size_t i = 0; i < projectedMap.size(); i++) {
                    // Check if old point projects to same screen location as new paint
                    float dx = projectedMap[i].x - newPt.pt.x;
                    float dy = projectedMap[i].y - newPt.pt.y;

                    if ((dx*dx + dy*dy) < (PRUNE_RADIUS_PX * PRUNE_RADIUS_PX)) {
                        keepMask[i] = false;
                        removeCount++;
                    }
                }
            }

            // Efficient Removal using Mask
            if (removeCount > 0) {
                // Rebuild 3D Points and Keypoints
                std::vector<cv::Point3f> new3D;
                std::vector<cv::KeyPoint> newKpt;
                new3D.reserve(mMapPoints3D.size() - removeCount);
                newKpt.reserve(mMapKeypoints.size() - removeCount);

                // Rebuild Descriptors
                cv::Mat newDesc;
                newDesc.reserve(mMapDescriptors.rows - removeCount); // cv::Mat reserve isn't standard but efficient push_back handles it

                for (size_t i = 0; i < keepMask.size(); i++) {
                    if (keepMask[i]) {
                        new3D.push_back(mMapPoints3D[i]);
                        newKpt.push_back(mMapKeypoints[i]);
                        newDesc.push_back(mMapDescriptors.row(i));
                    }
                }

                mMapPoints3D = std::move(new3D);
                mMapKeypoints = std::move(newKpt);
                mMapDescriptors = newDesc; // Move assignment

                LOGD("Teleology: Pruned %d old features.", removeCount);
            }
        }

        // Add new points
        if (!descriptorsToAdd.empty()) {
            mMapKeypoints.insert(mMapKeypoints.end(), pointsToAdd.begin(), pointsToAdd.end());
            mMapPoints3D.insert(mMapPoints3D.end(), points3DToAdd.begin(), points3DToAdd.end());
            cv::vconcat(mMapDescriptors, descriptorsToAdd, mMapDescriptors);
        }
    }
}

// --- Math Helpers ---

void MobileGS::MultiplyMatrixVector(const float* matrix, const float* in, float* out) {
    // Column-major multiplication (Standard OpenGL)
    for (int i = 0; i < 4; i++) {
        out[i] = 0.0f;
        for (int j = 0; j < 4; j++) {
            out[i] += matrix[j * 4 + i] * in[j];
        }
    }
}

cv::Point2f MobileGS::ProjectPoint(const cv::Point3f& p3d) {
    float vec4[4] = {p3d.x, p3d.y, p3d.z, 1.0f};
    float clip[4] = {0,0,0,0};

    // Model(World) -> View -> Projection
    // Combined ViewProj = Proj * View

    float viewSpace[4];
    MultiplyMatrixVector(mViewMatrix, vec4, viewSpace);
    MultiplyMatrixVector(mProjMatrix, viewSpace, clip);

    // Perspective Divide
    if (clip[3] != 0.0f) {
        float invW = 1.0f / clip[3];
        clip[0] *= invW;
        clip[1] *= invW;
    }

    // NDC (-1 to 1) -> Screen Coords
    // x = (x_ndc + 1) * width / 2
    // y = (1 - y_ndc) * height / 2  (Flip Y for image space)

    float screenX = (clip[0] + 1.0f) * 0.5f * mWidth;
    float screenY = (1.0f - clip[1]) * 0.5f * mHeight;

    return cv::Point2f(screenX, screenY);
}

cv::Point3f MobileGS::UnprojectPoint(const cv::KeyPoint& kpt, float depth) {
    // Inverse operation: Screen -> NDC -> View -> World
    // Simplified: Raycast from Camera Position (Inverse View) through pixel

    // 1. Screen -> NDC
    float ndcX = (kpt.pt.x / mWidth) * 2.0f - 1.0f;
    float ndcY = 1.0f - (kpt.pt.y / mHeight) * 2.0f; // Flip Y back

    // 2. Ray in View Space
    // Assuming standard projection matrix structure:
    // P[0] = 2n/w, P[5] = 2n/h
    // RayX = ndcX / P[0], RayY = ndcY / P[5], RayZ = -1

    float fovX = mProjMatrix[0];
    float fovY = mProjMatrix[5];

    float rayX = 0, rayY = 0, rayZ = -1.0f;
    if (fovX > 0) rayX = ndcX / fovX;
    if (fovY > 0) rayY = ndcY / fovY;

    // Scale ray by depth
    float viewX = rayX * depth;
    float viewY = rayY * depth;
    float viewZ = -depth; // Camera looks down -Z in OpenGL

    // 3. View -> World
    // World = Inverse(View) * Point
    // Since View is Rotation+Translation, Inv = Transpose(R) * (P - T)
    // Actually, simple way: standard matrix inversion or assume View is rigid body.

    // Construct Inverse View manually (Rotation Transpose + Translation recovery)
    // View = [ R  T ]
    //        [ 0  1 ]
    // InvView = [ R^T  -R^T * T ]
    //           [  0       1    ]

    // Extract Rotation columns (rows of View)
    float r00 = mViewMatrix[0], r01 = mViewMatrix[4], r02 = mViewMatrix[8];
    float r10 = mViewMatrix[1], r11 = mViewMatrix[5], r12 = mViewMatrix[9];
    float r20 = mViewMatrix[2], r21 = mViewMatrix[6], r22 = mViewMatrix[10];

    // Extract Translation
    float tx = mViewMatrix[12], ty = mViewMatrix[13], tz = mViewMatrix[14];

    // Calculate Camera Position (World Origin in Camera Space is -R^T * T)
    float camX = -(r00*tx + r10*ty + r20*tz);
    float camY = -(r01*tx + r11*ty + r21*tz);
    float camZ = -(r02*tx + r12*ty + r22*tz);

    // Rotate the Ray Vector (View -> World)
    // Ray_World = R^T * Ray_View
    float worldRayX = r00*viewX + r10*viewY + r20*viewZ;
    float worldRayY = r01*viewX + r11*viewY + r21*viewZ;
    float worldRayZ = r02*viewX + r12*viewY + r22*viewZ;

    return cv::Point3f(camX + worldRayX, camY + worldRayY, camZ + worldRayZ);
}

void MobileGS::Draw() {
    // Native OpenGL drawing logic would reside here.
    // Currently disabled as rendering is handled via pass-through to ArView
}