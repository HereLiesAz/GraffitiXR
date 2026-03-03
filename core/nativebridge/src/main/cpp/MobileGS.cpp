// FILE: core/nativebridge/src/main/cpp/MobileGS.cpp
#include "include/MobileGS.h"
#include <algorithm>
#include <android/log.h>
#include <cstring>
#include <vector>
#include <fstream>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GraffitiJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GraffitiJNI", __VA_ARGS__)

static constexpr size_t MAX_SPLATS = 500000;
static constexpr float VOXEL_SIZE = 0.02f; // 20mm voxels

static const char* kVertexShader =
        "#version 300 es\n"
        "layout(location = 0) in vec3 aPosition;\n"
        "layout(location = 1) in vec4 aColor;\n"
        "layout(location = 2) in float aConfidence;\n"
        "uniform mat4 uMvp;\n"
        "out vec4 vColor;\n"
        "void main() {\n"
        "  vec4 clip = uMvp * vec4(aPosition, 1.0);\n"
        "  gl_Position = clip;\n"
        "  float sz = (10.0 + 20.0 * aConfidence) / clip.w;\n"
        "  gl_PointSize = clamp(sz, 4.0, 128.0);\n"
        "  vColor = aColor;\n"
        "}\n";

static const char* kFragmentShader =
        "#version 300 es\n"
        "precision mediump float;\n"
        "in vec4 vColor;\n"
        "out vec4 oColor;\n"
        "void main() {\n"
        "  vec2 d = gl_PointCoord - 0.5;\n"
        "  float r2 = dot(d, d) * 4.0;\n"
        "  if (r2 > 1.0) discard;\n"
        "  float alpha = exp(-4.0 * r2);\n"
        "  oColor = vec4(vColor.rgb, alpha * vColor.a * 0.7);\n"
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

    splatData.reserve(MAX_SPLATS);
    LOGI("MobileGS initialized (CPU side) with %dx%d", width, height);
}

void MobileGS::initGl() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram != 0) return;
    initShaders();
    LOGI("MobileGS GL initialized");
}

void MobileGS::destroy() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram) { glDeleteProgram(mProgram); mProgram = 0; }
    if (mPointVbo) { glDeleteBuffers(1, &mPointVbo); mPointVbo = 0; }
    if (mMeshVbo) { glDeleteBuffers(1, &mMeshVbo); mMeshVbo = 0; }
}

void MobileGS::initShaders() {
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (!vertexShader || !fragmentShader) {
        LOGE("Shader compilation failed.");
        return;
    }
    mProgram = glCreateProgram();
    glAttachShader(mProgram, vertexShader);
    glAttachShader(mProgram, fragmentShader);
    glLinkProgram(mProgram);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    GLint linked = 0;
    glGetProgramiv(mProgram, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("Shader link failed.");
        glDeleteProgram(mProgram); mProgram = 0; return;
    }

    glGenBuffers(1, &mPointVbo);
    glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
    glBufferData(GL_ARRAY_BUFFER, MAX_SPLATS * sizeof(Splat), nullptr, GL_DYNAMIC_DRAW);

    glGenBuffers(1, &mMeshVbo);
    glBindBuffer(GL_ARRAY_BUFFER, mMeshVbo);
    glBufferData(GL_ARRAY_BUFFER, 1024 * 1024 * sizeof(float), nullptr, GL_DYNAMIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void MobileGS::setArCoreTrackingState(bool isTracking) {
    std::lock_guard<std::mutex> lock(mMutex);
    mIsArCoreTracking = isTracking;
}

bool MobileGS::isTracking() const { return mIsArCoreTracking; }

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

    for (int r = 0; r < depth.rows; r += step) {
        for (int c = 0; c < depth.cols; c += step) {
            float d = depth.at<float>(r, c);
            if (d > 0.1f && d < 5.0f) {
                float x_ndc = (c - halfW) / halfW;
                float y_ndc = -(r - halfH) / halfH;
                float xc = (x_ndc + cx) * d / fx;
                float yc = (y_ndc + cy) * d / fy;
                float zc = -d;

                float xw, yw, zw;
                camToWorld(V, xc, yc, zc, xw, yw, zw);

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
                    float alpha = s.confidence / (s.confidence + 1.0f);
                    s.x = s.x * alpha + xw * (1.0f - alpha);
                    s.y = s.y * alpha + yw * (1.0f - alpha);
                    s.z = s.z * alpha + zw * (1.0f - alpha);
                    s.r = s.r * alpha + r_f * (1.0f - alpha);
                    s.g = s.g * alpha + g_f * (1.0f - alpha);
                    s.b = s.b * alpha + b_f * (1.0f - alpha);
                    s.confidence = std::min(1.0f, s.confidence + 0.05f);
                } else {
                    splatData.push_back({xw, yw, zw, r_f, g_f, b_f, 1.0f, 0.1f});
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
}

void MobileGS::pruneMap() {
    if (splatData.size() < MAX_SPLATS) return;

    const size_t evictCount = MAX_SPLATS / 10;
    const size_t keepCount = splatData.size() - evictCount;

    // Move highest confidence to the front
    std::nth_element(splatData.begin(),
                     splatData.begin() + keepCount,
                     splatData.end(),[](const Splat& a, const Splat& b) {
                return a.confidence > b.confidence;
            });

    splatData.resize(keepCount);

    // Rebuild hash map to sync indices
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
    LOGI("Pruned voxel map down to %zu splats", splatData.size());
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
    LOGI("Target fingerprint registered with %d descriptors", mTargetDescriptors.rows);
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
            LOGI("Relocalization successful! Anchor matrix updated.");
        }
    }
}

void MobileGS::updateAnchorTransform(float* transformMat) {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(mAnchorMatrix, transformMat, 16 * sizeof(float));
    LOGI("Anchor transform manually updated.");
}

void MobileGS::saveModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) {
        LOGE("Failed to open %s for saving", path.c_str());
        return;
    }

    char magic[4] = {'G','X','R','M'};
    out.write(magic, 4);
    int version = 1;
    out.write((char*)&version, 4);
    int numSplats = splatData.size();
    out.write((char*)&numSplats, 4);
    int keyframes = 0;
    out.write((char*)&keyframes, 4);

    out.write((char*)splatData.data(), numSplats * sizeof(Splat));
    out.write((char*)mAnchorMatrix, 16 * sizeof(float));
    out.close();
    LOGI("Saved map to %s with %d splats", path.c_str(), numSplats);
}

void MobileGS::loadModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        LOGE("Failed to open %s for loading", path.c_str());
        return;
    }

    char magic[4];
    in.read(magic, 4);
    if (strncmp(magic, "GXRM", 4) != 0) {
        LOGE("Invalid map format");
        return;
    }

    int version, numSplats, keyframes;
    in.read((char*)&version, 4);
    in.read((char*)&numSplats, 4);
    in.read((char*)&keyframes, 4);

    splatData.resize(numSplats);
    in.read((char*)splatData.data(), numSplats * sizeof(Splat));
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
    LOGI("Loaded map from %s with %d splats", path.c_str(), numSplats);
}

void MobileGS::draw() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mProgram || !mCameraReady || mPointCount == 0) return;

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

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);

    glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(3 * sizeof(float)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(7 * sizeof(float)));

    glDrawArrays(GL_POINTS, 0, mPointCount);

    glDepthMask(GL_TRUE);
    glDisable(GL_BLEND);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}