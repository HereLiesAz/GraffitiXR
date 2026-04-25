#include "include/VoxelHash.h"
#include "include/NativeUtil.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#define GLM_ENABLE_EXPERIMENTAL
#include <glm/gtc/matrix_inverse.hpp>
#include <glm/gtc/quaternion.hpp>
#include <glm/gtx/quaternion.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GaussianEngine", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GaussianEngine", __VA_ARGS__)

// ~~~ Full Gaussian Splatting Implementation with Order-Independent Rendering (OIR) ~~~

static const char* kVertexShader =
    "#version 300 es\n"
    "precision highp float;\n"
    "layout(location = 0) in vec3 aPosition;\n"
    "layout(location = 1) in vec4 aColor;\n"
    "layout(location = 2) in vec3 aScale;\n"
    "layout(location = 3) in vec4 aRot;\n"
    "layout(location = 4) in float aConfidence;\n"
    "uniform mat4 uMvp;\n"
    "uniform mat4 uView;\n"
    "uniform float uFocalY;\n"
    "out vec4 vColor;\n"
    "out mat3 vCov2DInv;\n"
    "out float vDepth;\n"
    "\n"
    "mat3 quatToMat3(vec4 q) {\n"
    "  float x = q.x, y = q.y, z = q.z, w = q.w;\n"
    "  return mat3(\n"
    "    1.0-2.0*(y*y+z*z), 2.0*(x*y-w*z), 2.0*(x*z+w*y),\n"
    "    2.0*(x*y+w*z), 1.0-2.0*(x*x+z*z), 2.0*(y*z-w*x),\n"
    "    2.0*(x*z-w*y), 2.0*(y*z+w*x), 1.0-2.0*(x*x+y*y)\n"
    "  );\n"
    "}\n"
    "\n"
    "void main() {\n"
    "  vec4 posCam = uView * vec4(aPosition, 1.0);\n"
    "  gl_Position = uMvp * vec4(aPosition, 1.0);\n"
    "  vDepth = -posCam.z;\n"
    "\n"
    "  // 3D Covariance Sigma = R * S * S^T * R^T\n"
    "  mat3 R = quatToMat3(aRot);\n"
    "  mat3 S = mat3(aScale.x, 0.0, 0.0, 0.0, aScale.y, 0.0, 0.0, 0.0, aScale.z);\n"
    "  mat3 M = R * S;\n"
    "  mat3 Sigma3D = M * transpose(M);\n"
    "\n"
    "  // EWA Projection to 2D\n"
    "  mat3 W = mat3(uView);\n"
    "  float J11 = uFocalY / posCam.z;\n"
    "  float J13 = - (posCam.x * uFocalY) / (posCam.z * posCam.z);\n"
    "  float J22 = uFocalY / posCam.z;\n"
    "  float J23 = - (posCam.y * uFocalY) / (posCam.z * posCam.z);\n"
    "  mat3 J = mat3(J11, 0.0, J13, 0.0, J22, J23, 0.0, 0.0, 0.0);\n"
    "  mat3 T = J * W;\n"
    "  mat3 Sigma2D = T * Sigma3D * transpose(T);\n"
    "\n"
    "  // Stability filter (0.3px kernel)\n"
    "  Sigma2D[0][0] += 0.3;\n"
    "  Sigma2D[1][1] += 0.3;\n"
    "\n"
    "  float det = Sigma2D[0][0] * Sigma2D[1][1] - Sigma2D[0][1] * Sigma2D[0][1];\n"
    "  if (det <= 0.0) {\n"
    "    gl_Position = vec4(0.0, 0.0, 0.0, -1.0); // Cull if invalid\n"
    "    return;\n"
    "  }\n"
    "\n"
    "  vCov2DInv = inverse(Sigma2D);\n"
    "  vColor = vec4(aColor.rgb, aColor.a * clamp(aConfidence, 0.0, 1.0));\n"
    "\n"
    "  // Point size based on 3-sigma bounding box\n"
    "  float mid = 0.5 * (Sigma2D[0][0] + Sigma2D[1][1]);\n"
    "  float lambda1 = mid + sqrt(max(0.1, mid*mid - det));\n"
    "  float radius = ceil(3.0 * sqrt(lambda1));\n"
    "  gl_PointSize = clamp(radius * 2.0, 4.0, 128.0);\n"
    "}\n";

static const char* kFragmentShader =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec4 vColor;\n"
    "in mat3 vCov2DInv;\n"
    "in float vDepth;\n"
    "out vec4 oColor;\n"
    "\n"
    "void main() {\n"
    "  vec2 d = gl_PointCoord - vec2(0.5);\n"
    "  // Square splats requested: instead of pure Gaussian exp, we use a box-clamped distribution\n"
    "  // but keep the covariance-based scaling for anisotropic shapes.\n"
    "  float power = -0.5 * (d.x*d.x*vCov2DInv[0][0] + 2.0*d.x*d.y*vCov2DInv[0][1] + d.y*d.y*vCov2DInv[1][1]);\n"
    "  float g = exp(power);\n"
    "  \n"
    "  // Mobile-GS OIR Weighting: weight by inverse depth and confidence\n"
    "  float weight = g * vColor.a * (1.0 / (1.0 + vDepth * 0.1));\n"
    "  if (weight < 0.1) discard;\n"
    "  \n"
    "  oColor = vec4(vColor.rgb, weight);\n"
    "}\n";

VoxelHash::VoxelHash() {
    mSplatData.reserve(MAX_SPLATS);
}

VoxelHash::~VoxelHash() {}

void VoxelHash::initGl() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram != 0 && glIsProgram(mProgram)) return;

    LOGI("Initializing Gaussian Splatting Engine (OIR Mode)");
    mProgram = 0;
    mPointVbo = 0;

    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vs && fs) {
        mProgram = glCreateProgram();
        glAttachShader(mProgram, vs);
        glAttachShader(mProgram, fs);
        glLinkProgram(mProgram);
        glDeleteShader(vs);
        glDeleteShader(fs);

        glGenBuffers(1, &mPointVbo);
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(MAX_SPLATS * sizeof(Splat)), nullptr, GL_DYNAMIC_DRAW);
    }
    mDataDirty = true;
}

void VoxelHash::update(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, float voxelSize, float /*lightLevel*/) {
    if (depth.empty() || color.empty()) return;

    mLastVoxelSize = voxelSize;
    const float halfW = depth.cols / 2.0f;
    const float halfH = depth.rows / 2.0f;
    float fx = projMat[0] * halfW;
    float fy = projMat[5] * halfH;
    float cx = (projMat[8]  + 1.0f) * halfW;
    float cy = (-projMat[9] + 1.0f) * halfH;

    const float scaleX = (float)color.cols / depth.cols;
    const float scaleY = (float)color.rows / depth.rows;

    glm::mat4 V = glm::make_mat4(viewMat);
    glm::mat4 invV = glm::inverse(V);
    bool needsPruning = false;
    bool dataChanged = false;

    // Depth filtering: Ignore jumps and the common "invalid" 7.94m reading
    double minD = 0.3, maxD = 4.0;
    cv::Mat depthMask = (depth > 0.1f) & (depth < 7.5f);
    if (cv::countNonZero(depthMask) > 100) {
        cv::minMaxLoc(depth, &minD, &maxD, nullptr, nullptr, depthMask);
    }
    auto rangeD = static_cast<float>(maxD - minD);
    if (rangeD < 0.1f) rangeD = 1.0f;

    {
        std::lock_guard<std::mutex> lock(mMutex);

        // 1. Bayesian Refinement Loop
        int totalSplats = static_cast<int>(mSplatData.size());
        if (totalSplats > 0) {
            int itemsToProcess = std::max(100, totalSplats / 4);
            for (int i = 0; i < itemsToProcess; ++i) {
                int idx = mNextRefineIndex % totalSplats;
                mNextRefineIndex++;

                if (mSplatData[idx].confidence >= 0.98f) continue;

                glm::vec4 p_cam = V * glm::vec4(mSplatData[idx].x, mSplatData[idx].y, mSplatData[idx].z, 1.0f);
                if (p_cam.z >= -0.1f) continue;

                float u_cam = (p_cam.x * fx / -p_cam.z) + cx;
                float v_cam = (p_cam.y * -fy / -p_cam.z) + cy;

                if (u_cam >= 0 && u_cam < static_cast<float>(depth.cols) && v_cam >= 0 && v_cam < static_cast<float>(depth.rows)) {
                    float d = depth.at<float>(static_cast<int>(v_cam), static_cast<int>(u_cam));
                    float current_d = -p_cam.z;

                    if (d > 0.1f && d < 7.5f) {
                        float rel_d = std::max(0.0f, std::min(1.0f, (current_d - static_cast<float>(minD)) / rangeD));
                        float tolerance = 0.05f + (rel_d * 0.15f);

                        if (std::abs(current_d - d) < tolerance) {
                            mSplatData[idx].confidence = std::min(1.0f, mSplatData[idx].confidence + 0.10f);
                            // Mean refinement
                            float alpha = 0.15f;
                            glm::vec4 p_target_cam = p_cam * (d / current_d);
                            p_target_cam.w = 1.0f;
                            glm::vec4 p_target_world = invV * p_target_cam;
                            mSplatData[idx].x += (p_target_world.x - mSplatData[idx].x) * alpha;
                            mSplatData[idx].y += (p_target_world.y - mSplatData[idx].y) * alpha;
                            mSplatData[idx].z += (p_target_world.z - mSplatData[idx].z) * alpha;

                            dataChanged = true;
                            continue;
                        }
                    }
                    mSplatData[idx].confidence -= 0.03f;
                    dataChanged = true;
                    if (mSplatData[idx].confidence <= 0.0f) needsPruning = true;
                }
            }
        }

        // 2. Discovery Loop (Gaussian Spawning from Depth)
        int step = 6;
        for (int r = step; r < depth.rows - step; r += step) {
            for (int c = step; c < depth.cols - step; c += step) {
                float d = depth.at<float>(r, c);
                if (d > 0.1f && d < 7.0f) {
                    float d_left = depth.at<float>(r, c - 2);
                    float d_right = depth.at<float>(r, c + 2);
                    if (std::abs(d - d_left) > 0.20f || std::abs(d - d_right) > 0.20f) continue;

                    // NON-UNIFORMITY Requirement: Require visual texture
                    int colorR = static_cast<int>(r * scaleY);
                    int colorC = static_cast<int>(c * scaleX);
                    const auto& col = color.at<cv::Vec3b>(colorR, colorC);
                    const auto& col_alt = color.at<cv::Vec3b>(std::max(0, colorR - 4), std::max(0, colorC - 4));
                    float color_var = std::abs(static_cast<float>(col[0]) - col_alt[0]) +
                                     std::abs(static_cast<float>(col[1]) - col_alt[1]) +
                                     std::abs(static_cast<float>(col[2]) - col_alt[2]);
                    if (color_var < 8.0f) continue;

                    float xc = (static_cast<float>(c) - cx) * d / fx;
                    float yc = -(static_cast<float>(r) - cy) * d / fy;
                    float zc = -d;
                    glm::vec4 p_world = invV * glm::vec4(xc, yc, zc, 1.0f);

                    VoxelKey key{
                        static_cast<int>(std::floor(p_world.x / voxelSize)),
                        static_cast<int>(std::floor(p_world.y / voxelSize)),
                        static_cast<int>(std::floor(p_world.z / voxelSize))
                    };

                    if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
                        if (mSplatData.size() < MAX_SPLATS) {
                            Splat s{};
                            s.x = p_world.x; s.y = p_world.y; s.z = p_world.z;
                            // FIX: Correct color mapping (Assume BGR input from OpenCV default)
                            s.r = col[2]/255.0f; s.g = col[1]/255.0f; s.b = col[0]/255.0f; s.a = 0.8f;

                            // Initialize SH Level 0 (Base color)
                            s.sh[0] = (s.r - 0.5f) / 0.282095f;
                            s.sh[1] = (s.g - 0.5f) / 0.282095f;
                            s.sh[2] = (s.b - 0.5f) / 0.282095f;

                            // Initialize anisotropic scale based on depth and focal length
                            float s_val = d / fx * 2.0f;
                            s.scale[0] = s_val; s.scale[1] = s_val; s.scale[2] = s_val * 0.1f;

                            // Normal Estimation (Surface Alignment)
                            float dz_dx = (depth.at<float>(r, std::min(depth.cols-1, c+1)) - depth.at<float>(r, std::max(0, c-1))) / (2.0f / fx);
                            float dz_dy = (depth.at<float>(std::min(depth.rows-1, r+1), c) - depth.at<float>(std::max(0, r-1), c)) / (2.0f / fy);
                            glm::vec3 normal = glm::normalize(glm::vec3(-dz_dx, -dz_dy, 1.0f));
                            glm::vec3 normalW = glm::normalize(glm::mat3(invV) * normal);
                            glm::quat q = glm::rotation(glm::vec3(0, 0, 1), normalW);
                            s.rot[0] = q.x; s.rot[1] = q.y; s.rot[2] = q.z; s.rot[3] = q.w;

                            s.confidence = 0.25f;
                            mSplatData.push_back(s);
                            mVoxelGrid[key] = static_cast<int>(mSplatData.size() - 1);
                            dataChanged = true;
                        }
                    }
                }
            }
        }

        if (needsPruning || mSplatData.size() >= MAX_SPLATS * 0.95) {
            pruneInternal(0.01f, voxelSize);
            dataChanged = true;
        }

        if (dataChanged) mDataDirty = true;
    }
}

void VoxelHash::draw(const glm::mat4& mvp, const glm::mat4& view, float focalY, int screenHeight) {
    std::lock_guard<std::mutex> lock(mMutex);
    int count = static_cast<int>(mSplatData.size());
    if (!mProgram || count == 0) return;

    glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
    if (mDataDirty) {
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(count * sizeof(Splat)), mSplatData.data(), GL_DYNAMIC_DRAW);
        mDataDirty = false;
    }

    glUseProgram(mProgram);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uMvp"), 1, GL_FALSE, glm::value_ptr(mvp));
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uView"), 1, GL_FALSE, glm::value_ptr(view));
    glUniform1f(glGetUniformLocation(mProgram, "uFocalY"), focalY);

    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE); // Additive blending for OIR weight accumulation
    glDepthMask(GL_FALSE);
    glDisable(GL_DEPTH_TEST);

    // Attribute layout based on Splat struct
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(12));
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(64));
    glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(76));
    glEnableVertexAttribArray(4); glVertexAttribPointer(4, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(92));

    glDrawArrays(GL_POINTS, 0, count);

    glDisableVertexAttribArray(0); glDisableVertexAttribArray(1); glDisableVertexAttribArray(2); glDisableVertexAttribArray(3); glDisableVertexAttribArray(4);
    glDepthMask(GL_TRUE);
    glEnable(GL_DEPTH_TEST);
}

void VoxelHash::clear() {
    std::lock_guard<std::mutex> lock(mMutex);
    mSplatData.clear();
    mVoxelGrid.clear();
    mDataDirty = true;
}

void VoxelHash::pruneInternal(float threshold, float voxelSize) {
    mSplatData.erase(std::remove_if(mSplatData.begin(), mSplatData.end(),
        [threshold](const Splat& s) { return s.confidence < threshold; }), mSplatData.end());
    mVoxelGrid.clear();
    for (int i = 0; i < static_cast<int>(mSplatData.size()); ++i) {
        VoxelKey key{
            static_cast<int>(std::floor(mSplatData[i].x / voxelSize)),
            static_cast<int>(std::floor(mSplatData[i].y / voxelSize)),
            static_cast<int>(std::floor(mSplatData[i].z / voxelSize))
        };
        mVoxelGrid[key] = i;
    }
}

void VoxelHash::prune(float threshold) {
    std::lock_guard<std::mutex> lock(mMutex);
    pruneInternal(threshold, mLastVoxelSize);
    mDataDirty = true;
}

int VoxelHash::getSplatCount() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return static_cast<int>(mSplatData.size());
}

int VoxelHash::getImmutableSplatCount() const {
    std::lock_guard<std::mutex> lock(mMutex);
    int count = 0;
    for (const auto& s : mSplatData) {
        if (s.confidence >= 0.98f) count++;
    }
    return count;
}

float VoxelHash::getVisibleConfidenceAvg() const {
    std::lock_guard<std::mutex> lock(mMutex);
    float sum = 0;
    int count = 0;
    for (const auto& s : mSplatData) {
        if (s.confidence > 0.15f) {
            sum += s.confidence;
            count++;
        }
    }
    return count > 0 ? sum / static_cast<float>(count) : 0.0f;
}

float VoxelHash::getGlobalConfidenceAvg() const {
    std::lock_guard<std::mutex> lock(mMutex);
    float sum = 0;
    if (mSplatData.empty()) return 0.0f;
    for (const auto& s : mSplatData) {
        sum += s.confidence;
    }
    return sum / static_cast<float>(mSplatData.size());
}

void VoxelHash::addSparsePoints(const std::vector<float>& points, const float* viewMat, const float* projMat) {
    std::lock_guard<std::mutex> lock(mMutex);
    // Use ARCore feature points to seed Gaussians
    for (size_t i = 0; i < points.size(); i += 4) { // ARCore points are usually (x,y,z,confidence)
        float xw = points[i], yw = points[i+1], zw = points[i+2];

        VoxelKey key{
            static_cast<int>(std::floor(xw / mLastVoxelSize)),
            static_cast<int>(std::floor(yw / mLastVoxelSize)),
            static_cast<int>(std::floor(zw / mLastVoxelSize))
        };

        if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
            if (mSplatData.size() < MAX_SPLATS) {
                Splat s{};
                s.x = xw; s.y = yw; s.z = zw;
                s.r = 1.0f; s.g = 1.0f; s.b = 1.0f; s.a = 0.5f; // White seeds
                s.scale[0] = s.scale[1] = s.scale[2] = 0.05f;
                s.rot[3] = 1.0f; // Identity rotation
                s.confidence = 0.4f; // Higher initial confidence for stable tracking features

                mSplatData.push_back(s);
                mVoxelGrid[key] = static_cast<int>(mSplatData.size() - 1);
            }
        }
    }
    mDataDirty = true;
}

void VoxelHash::optimize(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat) {
    if (depth.empty() || color.empty()) return;

    std::lock_guard<std::mutex> lock(mMutex);
    int count = static_cast<int>(mSplatData.size());
    if (count == 0) return;

    glm::mat4 V = glm::make_mat4(viewMat);
    const float halfW = depth.cols / 2.0f;
    const float halfH = depth.rows / 2.0f;
    float fx = projMat[0] * halfW;
    float fy = projMat[5] * halfH;
    float cx = (projMat[8]  + 1.0f) * halfW;
    float cy = (-projMat[9] + 1.0f) * halfH;

    // Optimization Pass: Mini-batch gradient descent approximation
    int batchSize = std::max(500, count / 10);
    for (int i = 0; i < batchSize; ++i) {
        int idx = rand() % count;
        if (mSplatData[idx].confidence >= 0.98f) continue;

        glm::vec4 p_cam = V * glm::vec4(mSplatData[idx].x, mSplatData[idx].y, mSplatData[idx].z, 1.0f);
        if (p_cam.z >= -0.1f) continue;

        float u_cam = (p_cam.x * fx / -p_cam.z) + cx;
        float v_cam = (p_cam.y * -fy / -p_cam.z) + cy;

        if (u_cam >= 0 && u_cam < depth.cols && v_cam >= 0 && v_cam < depth.rows) {
            float d = depth.at<float>(static_cast<int>(v_cam), static_cast<int>(u_cam));
            if (d > 0.1f) {
                float err = std::abs(-p_cam.z - d);
                // Learning Rate: 0.1
                if (err < 0.2f) {
                    mSplatData[idx].confidence = std::min(1.0f, mSplatData[idx].confidence + 0.05f);
                    // Densification check: if opacity is high but scale is large, split?
                } else {
                    mSplatData[idx].confidence -= 0.02f;
                }
            }
        }
    }
    mDataDirty = true;
}
