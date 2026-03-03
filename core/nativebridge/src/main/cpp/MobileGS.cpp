// ~~~ FILE: ./core/nativebridge/src/main/cpp/MobileGS.cpp ~~~
#include "include/MobileGS.h"
#include <algorithm>
#include <android/log.h>
#include <cstring>
#include <vector>
#include <fstream>
#include <cmath>
#include <numeric>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GraffitiJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GraffitiJNI", __VA_ARGS__)

static constexpr size_t MAX_SPLATS = 500000;
static constexpr float VOXEL_SIZE = 0.02f; // 20mm voxels

// ANISOTROPIC SHADER: Accepts Normal and Radius, calculates View Angle (NdotV)
static const char* kVertexShader =
        "#version 300 es\n"
        "layout(location = 0) in vec3 aPosition;\n"
        "layout(location = 1) in vec4 aColor;\n"
        "layout(location = 2) in float aConfidence;\n"
        "layout(location = 3) in vec3 aNormal;\n"
        "layout(location = 4) in float aRadius;\n"
        "uniform mat4 uMvp;\n"
        "uniform vec3 uCameraPos;\n"
        "out vec4 vColor;\n"
        "out float vNdotV;\n"
        "void main() {\n"
        "  vec4 clip = uMvp * vec4(aPosition, 1.0);\n"
        "  gl_Position = clip;\n"
        "  vec3 viewDir = normalize(uCameraPos - aPosition);\n"
        "  vNdotV = abs(dot(aNormal, viewDir));\n"
        "  float sz = (10.0 + 30.0 * aConfidence) * aRadius / clip.w;\n"
        "  gl_PointSize = clamp(sz, 4.0, 128.0);\n"
        "  vColor = aColor;\n"
        "}\n";

// ANISOTROPIC SHADER: Flattens splat edge-on using NdotV
static const char* kFragmentShader =
        "#version 300 es\n"
        "precision mediump float;\n"
        "in vec4 vColor;\n"
        "in float vNdotV;\n"
        "out vec4 oColor;\n"
        "void main() {\n"
        "  vec2 d = gl_PointCoord - 0.5;\n"
        "  float r2 = dot(d, d) * 4.0;\n"
        "  if (r2 > 1.0) discard;\n"
        "  float alpha = exp(-4.0 * r2);\n"
        "  float finalAlpha = alpha * vColor.a * vNdotV * 0.9;\n"
        "  oColor = vec4(vColor.rgb, finalAlpha);\n"
        "}\n";

static GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            char* infoLog = (char*)malloc(infoLen);
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog);
            LOGE("Error compiling shader:\n%s\n", infoLog);
            free(infoLog);
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

void MobileGS::initialize(int width, int height) {
    std::lock_guard<std::mutex> lock(mMutex);
    mScreenWidth = width;
    mScreenHeight = height;
    mFeatureDetector = cv::ORB::create(500);
    mMatcher = cv::DescriptorMatcher::create("BruteForce-Hamming");

    memset(mViewMatrix, 0, sizeof(mViewMatrix));
    memset(mProjMatrix, 0, sizeof(mProjMatrix));
    memset(mAnchorMatrix, 0, sizeof(mAnchorMatrix));
    mViewMatrix[0] = mViewMatrix[5] = mViewMatrix[10] = mViewMatrix[15] = 1.0f;
    mProjMatrix[0] = mProjMatrix[5] = mProjMatrix[10] = mProjMatrix[15] = 1.0f;
    mAnchorMatrix[0] = mAnchorMatrix[5] = mAnchorMatrix[10] = mAnchorMatrix[15] = 1.0f;

    mFrameCounter = 0;
    splatData.reserve(MAX_SPLATS);
    mDrawIndices.reserve(MAX_SPLATS);

    // Start Background Sorter Thread
    if (!mSortRunning) {
        mSortRunning = true;
        mSortThread = std::thread(&MobileGS::sortThreadFunc, this);
    }

    LOGI("MobileGS initialized (CPU side) with %dx%d", width, height);
}

void MobileGS::initGl() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram != 0) return;
    initShaders();
    LOGI("MobileGS GL initialized");
}

void MobileGS::destroy() {
    // Stop Sort Thread cleanly
    if (mSortRunning) {
        mSortRunning = false;
        mSortCv.notify_all();
        if (mSortThread.joinable()) {
            mSortThread.join();
        }
    }

    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram) { glDeleteProgram(mProgram); mProgram = 0; }
    if (mPointVbo) { glDeleteBuffers(1, &mPointVbo); mPointVbo = 0; }
    if (mIndexVbo) { glDeleteBuffers(1, &mIndexVbo); mIndexVbo = 0; }
}

void MobileGS::initShaders() {
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (!vertexShader || !fragmentShader) return;

    mProgram = glCreateProgram();
    glAttachShader(mProgram, vertexShader);
    glAttachShader(mProgram, fragmentShader);
    glLinkProgram(mProgram);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    glGenBuffers(1, &mPointVbo);
    glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
    glBufferData(GL_ARRAY_BUFFER, MAX_SPLATS * sizeof(Splat), nullptr, GL_DYNAMIC_DRAW);

    glGenBuffers(1, &mIndexVbo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIndexVbo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, MAX_SPLATS * sizeof(uint32_t), nullptr, GL_DYNAMIC_DRAW);

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
}

void MobileGS::setArCoreTrackingState(bool isTracking) {
    std::lock_guard<std::mutex> lock(mMutex);
    mIsArCoreTracking = isTracking;
}

bool MobileGS::isTracking() const { return mIsArCoreTracking; }

// Extracts world position of camera from View Matrix (Camera-to-World translation)
cv::Point3f MobileGS::getCameraWorldPosition() const {
    float r00 = mViewMatrix[0], r10 = mViewMatrix[1], r20 = mViewMatrix[2];
    float r01 = mViewMatrix[4], r11 = mViewMatrix[5], r21 = mViewMatrix[6];
    float r02 = mViewMatrix[8], r12 = mViewMatrix[9], r22 = mViewMatrix[10];
    float tx = mViewMatrix[12], ty = mViewMatrix[13], tz = mViewMatrix[14];

    float cx = -(r00*tx + r10*ty + r20*tz);
    float cy = -(r01*tx + r11*ty + r21*tz);
    float cz = -(r02*tx + r12*ty + r22*tz);
    return cv::Point3f(cx, cy, cz);
}

// Background Thread: Sorts splats back-to-front based on camera position
void MobileGS::sortThreadFunc() {
    while (mSortRunning) {
        std::vector<cv::Point3f> positions;
        cv::Point3f camPos;
        int currentCount = 0;

        {
            std::unique_lock<std::mutex> lock(mSortMutex);
            mSortCv.wait(lock, [this] { return mSortRequested || !mSortRunning; });
            if (!mSortRunning) break;

            std::lock_guard<std::mutex> mainLock(mMutex);
            currentCount = mPointCount;
            positions.resize(currentCount);
            for (int i = 0; i < currentCount; ++i) {
                positions[i] = cv::Point3f(splatData[i].x, splatData[i].y, splatData[i].z);
            }
            camPos = getCameraWorldPosition();
            mSortRequested = false;
        }

        if (currentCount == 0) continue;

        std::vector<uint32_t> indices(currentCount);
        std::iota(indices.begin(), indices.end(), 0);

        // Sort descending (furthest first) for correct Alpha Blending
        std::sort(indices.begin(), indices.end(), [&](uint32_t a, uint32_t b) {
            cv::Point3f da = positions[a] - camPos;
            cv::Point3f db = positions[b] - camPos;
            return (da.x*da.x + da.y*da.y + da.z*da.z) > (db.x*db.x + db.y*db.y + db.z*db.z);
        });

        {
            std::lock_guard<std::mutex> lock(mSortMutex);
            mDrawIndices = std::move(indices);
            mIndicesDirty = true;
        }
    }
}

static void camToWorld(const float* V, float xc, float yc, float zc,
                       float& xw, float& yw, float& zw) {
    float R[9] = { V[0], V[4], V[8], V[1], V[5], V[9], V[2], V[6], V[10] };
    float t[3] = { V[12], V[13], V[14] };
    float dx = xc - t[0];
    float dy = yc - t[1];
    float dz = zc - t[2];
    xw = R[0]*dx + R[3]*dy + R[6]*dz;
    yw = R[1]*dx + R[4]*dy + R[7]*dz;
    zw = R[2]*dx + R[5]*dy + R[8]*dz;
}

static void camToWorldNormal(const float* V, float nxc, float nyc, float nzc,
                             float& nxw, float& nyw, float& nzw) {
    // Transform normal using only the Rotation matrix
    float R[9] = { V[0], V[4], V[8], V[1], V[5], V[9], V[2], V[6], V[10] };
    nxw = R[0]*nxc + R[3]*nyc + R[6]*nzc;
    nyw = R[1]*nxc + R[4]*nyc + R[7]*nzc;
    nzw = R[2]*nxc + R[5]*nyc + R[8]*nzc;
}

void MobileGS::processDepthFrame(const cv::Mat& depth, const cv::Mat& color) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mIsArCoreTracking || depth.empty() || color.empty() || !mCameraReady) return;

    const float* V = mViewMatrix;
    float fx = mProjMatrix[0];
    float fy = mProjMatrix[5];
    float cx = mProjMatrix[8];
    float cy = mProjMatrix[9];

    const float halfW = depth.cols / 2.0f;
    const float halfH = depth.rows / 2.0f;

    bool mapModified = false;
    int step = 8;

    auto unproject = [&](int r, int c, float d) {
        float x_ndc = (c - halfW) / halfW;
        float y_ndc = -(r - halfH) / halfH;
        return cv::Point3f((x_ndc + cx) * d / fx, (y_ndc + cy) * d / fy, -d);
    };

    for (int r = 0; r < depth.rows - step; r += step) {
        for (int c = 0; c < depth.cols - step; c += step) {
            float d = depth.at<float>(r, c);
            float d_r = depth.at<float>(r, c + step);
            float d_d = depth.at<float>(r + step, c);

            if (d > 0.1f && d < 5.0f && d_r > 0.1f && d_d > 0.1f) {
                // NORMAL ESTIMATION (Cross Product of depth gradients)
                cv::Point3f p_cam = unproject(r, c, d);
                cv::Point3f p_r_cam = unproject(r, c + step, d_r);
                cv::Point3f p_d_cam = unproject(r + step, c, d_d);

                cv::Point3f v1 = p_r_cam - p_cam;
                cv::Point3f v2 = p_d_cam - p_cam;
                cv::Point3f n_cam = v1.cross(v2);

                // Ensure normal faces camera (camera is at 0,0,0 in cam space)
                if (n_cam.dot(p_cam) > 0) {
                    n_cam = -n_cam;
                }

                float n_len = cv::norm(n_cam);
                if (n_len > 0.0001f) n_cam /= n_len;

                float xw, yw, zw;
                camToWorld(V, p_cam.x, p_cam.y, p_cam.z, xw, yw, zw);

                float nx_w, ny_w, nz_w;
                camToWorldNormal(V, n_cam.x, n_cam.y, n_cam.z, nx_w, ny_w, nz_w);

                VoxelKey key{
                        static_cast<int>(std::floor(xw / VOXEL_SIZE)),
                        static_cast<int>(std::floor(yw / VOXEL_SIZE)),
                        static_cast<int>(std::floor(zw / VOXEL_SIZE))
                };

                cv::Vec3b col = color.at<cv::Vec3b>(r, c);
                float r_f = col[0]/255.0f, g_f = col[1]/255.0f, b_f = col[2]/255.0f;

                auto it = mVoxelGrid.find(key);
                if (it != mVoxelGrid.end()) {
                    Splat& s = splatData[it->second];

                    float colorDist = std::abs(s.r - r_f) + std::abs(s.g - g_f) + std::abs(s.b - b_f);
                    float alpha = s.confidence / (s.confidence + 1.0f);

                    if (colorDist > 0.8f) {
                        s.confidence *= 0.5f;
                        alpha = 0.2f;
                    }

                    s.x = s.x * alpha + xw * (1.0f - alpha);
                    s.y = s.y * alpha + yw * (1.0f - alpha);
                    s.z = s.z * alpha + zw * (1.0f - alpha);
                    s.r = s.r * alpha + r_f * (1.0f - alpha);
                    s.g = s.g * alpha + g_f * (1.0f - alpha);
                    s.b = s.b * alpha + b_f * (1.0f - alpha);

                    // Slerp normal (simplified lerp + normalize for speed)
                    s.nx = s.nx * alpha + nx_w * (1.0f - alpha);
                    s.ny = s.ny * alpha + ny_w * (1.0f - alpha);
                    s.nz = s.nz * alpha + nz_w * (1.0f - alpha);
                    float len = std::sqrt(s.nx*s.nx + s.ny*s.ny + s.nz*s.nz);
                    if(len > 0) { s.nx/=len; s.ny/=len; s.nz/=len; }

                    s.confidence = std::min(1.0f, s.confidence + 0.05f);
                } else {
                    splatData.push_back({xw, yw, zw, r_f, g_f, b_f, 1.0f, 0.1f, nx_w, ny_w, nz_w, 1.5f});
                    mVoxelGrid[key] = splatData.size() - 1;
                }
                mapModified = true;
            }
        }
    }

    if (splatData.size() >= MAX_SPLATS) {
        pruneMap();
    }

    mPointCount = static_cast<int>(splatData.size());
    if (mPointVbo != 0 && mapModified) {
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, splatData.size() * sizeof(Splat), splatData.data());
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    if (++mFrameCounter % 30 == 0) {
        continuousOptimize();
        // Wake up the sorter thread
        {
            std::lock_guard<std::mutex> sortLock(mSortMutex);
            mSortRequested = true;
        }
        mSortCv.notify_one();
    }
}

void MobileGS::continuousOptimize() {
    if (splatData.empty()) return;

    bool needsRebuild = false;
    size_t validCount = 0;

    for (size_t i = 0; i < splatData.size(); i++) {
        splatData[i].confidence -= 0.005f;

        if (splatData[i].confidence > 0.0f) {
            if (validCount != i) {
                splatData[validCount] = splatData[i];
            }
            validCount++;
        } else {
            needsRebuild = true;
        }
    }

    if (needsRebuild) {
        splatData.resize(validCount);
        mVoxelGrid.clear();
        for (size_t i = 0; i < splatData.size(); ++i) {
            VoxelKey key{
                    static_cast<int>(std::floor(splatData[i].x / VOXEL_SIZE)),
                    static_cast<int>(std::floor(splatData[i].y / VOXEL_SIZE)),
                    static_cast<int>(std::floor(splatData[i].z / VOXEL_SIZE))
            };
            mVoxelGrid[key] = i;
        }
        mPointCount = static_cast<int>(validCount);
    }
}

void MobileGS::pruneMap() {
    if (splatData.size() < MAX_SPLATS) return;

    const size_t evictCount = MAX_SPLATS / 10;
    const size_t keepCount = splatData.size() - evictCount;

    std::nth_element(splatData.begin(),
                     splatData.begin() + keepCount,
                     splatData.end(),[](const Splat& a, const Splat& b) {
                return a.confidence > b.confidence;
            });

    splatData.resize(keepCount);

    mVoxelGrid.clear();
    for (size_t i = 0; i < splatData.size(); ++i) {
        const auto& s = splatData[i];
        VoxelKey key{
                static_cast<int>(std::floor(s.x / VOXEL_SIZE)),
                static_cast<int>(std::floor(s.y / VOXEL_SIZE)),
                static_cast<int>(std::floor(s.z / VOXEL_SIZE))
        };
        mVoxelGrid[key] = i;
    }
}

void MobileGS::updateCamera(float* viewMat, float* projMat) {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(mViewMatrix, viewMat, 16 * sizeof(float));
    memcpy(mProjMatrix, projMat, 16 * sizeof(float));
    mCameraReady = true;
}

void MobileGS::setTargetFingerprint(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d) {
    std::lock_guard<std::mutex> lock(mMutex);
    mTargetDescriptors = descriptors.clone();
    mTargetKeypoints3D = points3d;
}

void MobileGS::attemptRelocalization(const cv::Mat& colorFrame) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mIsArCoreTracking || mTargetDescriptors.empty() || mTargetKeypoints3D.empty() || colorFrame.empty()) return;

    cv::Mat gray;
    cv::cvtColor(colorFrame, gray, cv::COLOR_RGB2GRAY);

    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;
    mFeatureDetector->detectAndCompute(gray, cv::noArray(), kps, descs);
    if (descs.empty()) return;

    std::vector<cv::DMatch> matches;
    mMatcher->match(mTargetDescriptors, descs, matches);

    std::vector<cv::Point3f> objPts;
    std::vector<cv::Point2f> imgPts;
    for (const auto& m : matches) {
        if (m.distance < 50.0f && m.queryIdx < mTargetKeypoints3D.size()) {
            objPts.push_back(mTargetKeypoints3D[m.queryIdx]);
            imgPts.push_back(kps[m.trainIdx].pt);
        }
    }

    if (objPts.size() >= 10) {
        cv::Mat rvec, tvec;
        float fx = mProjMatrix[0] * mScreenWidth / 2.0f;
        float fy = mProjMatrix[5] * mScreenHeight / 2.0f;
        float cx = mScreenWidth / 2.0f;
        float cy = mScreenHeight / 2.0f;

        cv::Mat cameraMatrix = (cv::Mat_<double>(3, 3) << fx, 0, cx, 0, fy, cy, 0, 0, 1);
        cv::Mat distCoeffs = cv::Mat::zeros(4, 1, CV_64F);

        bool success = cv::solvePnPRansac(objPts, imgPts, cameraMatrix, distCoeffs, rvec, tvec);
        if (success) {
            cv::Mat R;
            cv::Rodrigues(rvec, R);
            cv::Mat T = cv::Mat::eye(4, 4, CV_32F);
            for (int i=0; i<3; ++i) {
                for (int j=0; j<3; ++j) {
                    T.at<float>(i, j) = R.at<double>(i, j);
                }
                T.at<float>(i, 3) = tvec.at<double>(i, 0);
            }
            memcpy(mAnchorMatrix, T.data, 16 * sizeof(float));
        }
    }
}

void MobileGS::updateAnchorTransform(float* transformMat) {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(mAnchorMatrix, transformMat, 16 * sizeof(float));
}

void MobileGS::saveModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) return;

    char magic[4] = {'G','X','R','M'};
    out.write(magic, 4);
    int version = 3; // Version 3 handles 48-byte anisotropic Splat structs
    out.write((char*)&version, 4);
    int numSplats = splatData.size();
    out.write((char*)&numSplats, 4);
    int keyframes = 0;
    out.write((char*)&keyframes, 4);

    out.write((char*)splatData.data(), numSplats * sizeof(Splat));
    out.write((char*)mAnchorMatrix, 16 * sizeof(float));
    out.close();
}

void MobileGS::loadModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return;

    char magic[4];
    in.read(magic, 4);
    if (strncmp(magic, "GXRM", 4) != 0) return;

    int version, numSplats, keyframes;
    in.read((char*)&version, 4);
    in.read((char*)&numSplats, 4);
    in.read((char*)&keyframes, 4);

    // Backward compatibility handles
    if (version == 3) {
        splatData.resize(numSplats);
        in.read((char*)splatData.data(), numSplats * sizeof(Splat));
    } else if (version == 2) {
        // Upgrade legacy 32-byte structs to 48-byte structs
        struct LegacySplat { float x,y,z, r,g,b,a, conf; };
        std::vector<LegacySplat> legacy(numSplats);
        in.read((char*)legacy.data(), numSplats * sizeof(LegacySplat));
        splatData.resize(numSplats);
        for(int i=0; i<numSplats; i++) {
            splatData[i] = {legacy[i].x, legacy[i].y, legacy[i].z,
                            legacy[i].r, legacy[i].g, legacy[i].b, legacy[i].a,
                            legacy[i].conf,
                            0.0f, 0.0f, 1.0f, 1.0f}; // Default forward normal
        }
    } else {
        return; // Unsupported format
    }

    in.read((char*)mAnchorMatrix, 16 * sizeof(float));
    in.close();

    mPointCount = numSplats;
    mVoxelGrid.clear();
    for (size_t i = 0; i < splatData.size(); ++i) {
        const auto& s = splatData[i];
        VoxelKey key{
                static_cast<int>(std::floor(s.x / VOXEL_SIZE)),
                static_cast<int>(std::floor(s.y / VOXEL_SIZE)),
                static_cast<int>(std::floor(s.z / VOXEL_SIZE))
        };
        mVoxelGrid[key] = i;
    }

    if (mPointVbo != 0 && mPointCount > 0) {
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, splatData.size() * sizeof(Splat), splatData.data());
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }
}

void MobileGS::draw() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mProgram || !mCameraReady || mPointCount == 0) return;

    // Check if sorter thread finished a new batch
    {
        std::lock_guard<std::mutex> sortLock(mSortMutex);
        if (mIndicesDirty) {
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIndexVbo);
            glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, 0, mDrawIndices.size() * sizeof(uint32_t), mDrawIndices.data());
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
            mIndicesDirty = false;
        }
    }

    glEnable(GL_DEPTH_TEST);
    glUseProgram(mProgram);

    // Apply Teleological Anchor to View
    float va[16];
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += mViewMatrix[k*4 + row] * mAnchorMatrix[col*4 + k];
            va[col*4 + row] = s;
        }
    }

    // Apply Projection
    float mvp[16];
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += mProjMatrix[k*4 + row] * va[col*4 + k];
            mvp[col*4 + row] = s;
        }
    }

    GLint mvpLoc = glGetUniformLocation(mProgram, "uMvp");
    glUniformMatrix4fv(mvpLoc, 1, GL_FALSE, mvp);

    cv::Point3f camPos = getCameraWorldPosition();
    GLint camLoc = glGetUniformLocation(mProgram, "uCameraPos");
    glUniform3f(camLoc, camPos.x, camPos.y, camPos.z);

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE); // Critical: Depth test is ON, but depth WRITE is OFF for soft transparency.

    glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);

    // Wire up the 48-byte Splat struct
    glEnableVertexAttribArray(0); // aPosition (vec3)
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)0);

    glEnableVertexAttribArray(1); // aColor (vec4)
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(12));

    glEnableVertexAttribArray(2); // aConfidence (float)
    glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(28));

    glEnableVertexAttribArray(3); // aNormal (vec3)
    glVertexAttribPointer(3, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(32));

    glEnableVertexAttribArray(4); // aRadius (float)
    glVertexAttribPointer(4, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(44));

    // RENDER USING SORTED INDEX BUFFER
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIndexVbo);

    // We only draw up to the number of indices that have been sorted.
    // If mDrawIndices is smaller than mPointCount (due to rapid creation), it's fine, we catch up next sort cycle.
    int elementsToDraw = std::min(mPointCount, static_cast<int>(mDrawIndices.size()));
    if (elementsToDraw > 0) {
        glDrawElements(GL_POINTS, elementsToDraw, GL_UNSIGNED_INT, (void*)0);
    } else {
        // Fallback if thread hasn't sorted the first batch yet
        glDrawArrays(GL_POINTS, 0, mPointCount);
    }

    glDepthMask(GL_TRUE);
    glDisable(GL_BLEND);

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glDisableVertexAttribArray(2);
    glDisableVertexAttribArray(3);
    glDisableVertexAttribArray(4);

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
}