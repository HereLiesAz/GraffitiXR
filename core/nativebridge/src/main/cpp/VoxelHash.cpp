#include "include/VoxelHash.h"
#include "include/NativeUtil.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#define GLM_ENABLE_EXPERIMENTAL
#include <glm/gtc/matrix_inverse.hpp>
#include <glm/gtc/quaternion.hpp>
#include <glm/gtx/quaternion.hpp>
#include <glm/gtx/norm.hpp>
#include <glm/gtx/component_wise.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GaussianEngine", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GaussianEngine", __VA_ARGS__)

// ~~~ Advanced Gaussian Splatting Implementation ~~~

static const char* kVertexShader =
    "#version 300 es\n"
    "precision highp float;\n"
    "layout(location = 0) in vec3 aPosition;\n"
    "layout(location = 1) in vec4 aColor;\n"
    "layout(location = 2) in vec3 aScale;\n"
    "layout(location = 3) in vec4 aRot;\n"
    "layout(location = 4) in float aConfidence;\n"
    "layout(location = 5) in vec3 aVelocity;\n"
    "layout(location = 6) in vec3 aSH_R;\n"
    "layout(location = 7) in vec3 aSH_G;\n"
    "layout(location = 8) in vec3 aSH_B;\n"
    "uniform mat4 uMvp;\n"
    "uniform mat4 uView;\n"
    "uniform float uFocalY;\n"
    "uniform float uTimeStep;\n"
    "uniform vec3 uCamPos;\n"
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
    "  vec3 integratedPos = aPosition + aVelocity * uTimeStep;\n"
    "  vec4 posCam = uView * vec4(integratedPos, 1.0);\n"
    "  gl_Position = uMvp * vec4(integratedPos, 1.0);\n"
    "  vDepth = -posCam.z;\n"
    "\n"
    "  vec3 dir = normalize(integratedPos - uCamPos);\n"
    "  float SH_C1 = 0.4886025;\n"
    "  vec3 resColor = aColor.rgb;\n"
    "  resColor.r += SH_C1 * (aSH_R.x * dir.y + aSH_R.y * dir.z + aSH_R.z * dir.x);\n"
    "  resColor.g += SH_C1 * (aSH_G.x * dir.y + aSH_G.y * dir.z + aSH_G.z * dir.x);\n"
    "  resColor.b += SH_C1 * (aSH_B.x * dir.y + aSH_B.y * dir.z + aSH_B.z * dir.x);\n"
    "\n"
    "  mat3 R = quatToMat3(aRot);\n"
    "  mat3 S = mat3(aScale.x, 0.0, 0.0, 0.0, aScale.y, 0.0, 0.0, 0.0, aScale.z);\n"
    "  mat3 M = R * S;\n"
    "  mat3 Sigma3D = M * transpose(M);\n"
    "\n"
    "  mat3 W = mat3(uView);\n"
    "  float J11 = uFocalY / posCam.z;\n"
    "  float J13 = - (posCam.x * uFocalY) / (posCam.z * posCam.z);\n"
    "  float J22 = uFocalY / posCam.z;\n"
    "  float J23 = - (posCam.y * uFocalY) / (posCam.z * posCam.z);\n"
    "  mat3 J = mat3(J11, 0.0, J13, 0.0, J22, J23, 0.0, 0.0, 0.0);\n"
    "  mat3 T = J * W;\n"
    "  mat3 Sigma2D = T * Sigma3D * transpose(T);\n"
    "\n"
    "  Sigma2D[0][0] += 0.3;\n"
    "  Sigma2D[1][1] += 0.3;\n"
    "\n"
    "  float det = Sigma2D[0][0] * Sigma2D[1][1] - Sigma2D[0][1] * Sigma2D[0][1];\n"
    "  if (det <= 0.0) { gl_Position = vec4(0,0,0,-1); return; }\n"
    "\n"
    "  vCov2DInv = inverse(Sigma2D);\n"
    "  vColor = vec4(max(vec3(0.0), resColor), aColor.a * clamp(aConfidence, 0.0, 1.0));\n"
    "\n"
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
    "  float power = -0.5 * (d.x*d.x*vCov2DInv[0][0] + 2.0*d.x*d.y*vCov2DInv[0][1] + d.y*d.y*vCov2DInv[1][1]);\n"
    "  float g = exp(power);\n"
    "  float weight = g * vColor.a * (1.0 / (1.0 + vDepth * 0.05));\n"
    "  if (weight < 0.1) discard;\n"
    "  oColor = vec4(vColor.rgb, weight);\n"
    "}\n";

VoxelHash::VoxelHash() : mNextRefineIndex(0) {
    mSplatData.reserve(MAX_SPLATS);
}

VoxelHash::~VoxelHash() {}

void VoxelHash::initGl() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram != 0 && glIsProgram(mProgram)) return;
    LOGI("Initializing Advanced Gaussian Engine");
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
    const float halfW = (float)depth.cols / 2.0f;
    const float halfH = (float)depth.rows / 2.0f;
    float fx = projMat[0] * halfW;
    float fy = projMat[5] * halfH;
    float cx = (projMat[8]  + 1.0f) * halfW;
    float cy = (-projMat[9] + 1.0f) * halfH;
    const float scaleX = (float)color.cols / depth.cols;
    const float scaleY = (float)color.rows / depth.rows;

    glm::mat4 V = glm::make_mat4(viewMat);
    glm::mat4 invV = glm::inverse(V);
    bool dataChanged = false;

    double minD = 0.3, maxD = 4.0;
    cv::Mat depthMask = (depth > 0.1f) & (depth < 7.5f);
    if (cv::countNonZero(depthMask) > 100) {
        cv::minMaxLoc(depth, &minD, &maxD, nullptr, nullptr, depthMask);
    }

    {
        std::lock_guard<std::mutex> lock(mMutex);

        // 1. Refinement Loop: Subset optimization for efficiency
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

                if (u_cam >= 0 && u_cam < (float)depth.cols && v_cam >= 0 && v_cam < (float)depth.rows) {
                    float d = depth.at<float>(static_cast<int>(v_cam), static_cast<int>(u_cam));
                    if (d > 0.1f) {
                        float current_d = -p_cam.z;
                        if (std::abs(current_d - d) < 0.10f) {
                            mSplatData[idx].confidence = std::min(1.0f, mSplatData[idx].confidence + 0.15f);
                            dataChanged = true;
                            continue;
                        }
                    }
                    mSplatData[idx].confidence -= 0.05f;
                    dataChanged = true;
                }
            }
        }

        // 2. Discovery loop
        int step = 6;
        for (int r = step; r < depth.rows - step; r += step) {
            for (int c = step; c < depth.cols - step; c += step) {
                float d = depth.at<float>(r, c);
                if (d > 0.1f && d < 7.0f) {
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

                    VoxelKey key{ static_cast<int>(std::floor(p_world.x / voxelSize)), static_cast<int>(std::floor(p_world.y / voxelSize)), static_cast<int>(std::floor(p_world.z / voxelSize)) };

                    if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
                        if (mSplatData.size() < MAX_SPLATS) {
                            Splat s{};
                            s.x = p_world.x; s.y = p_world.y; s.z = p_world.z;
                            s.r = col[2]/255.0f; s.g = col[1]/255.0f; s.b = col[0]/255.0f; s.a = 0.5f;

                            s.sh[0] = (s.r - 0.5f) / 0.282095f;
                            s.sh[1] = (s.g - 0.5f) / 0.282095f;
                            s.sh[2] = (s.b - 0.5f) / 0.282095f;

                            float s_val = d / fx * 2.0f;
                            s.scale[0] = s_val; s.scale[1] = s_val; s.scale[2] = s_val * 0.1f;

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
        if (dataChanged) mDataDirty = true;
    }
}

void VoxelHash::addSparsePoints(const std::vector<float>& points, const float* viewMat, const float* projMat) {
    std::lock_guard<std::mutex> lock(mMutex);
    for (size_t i = 0; i < points.size(); i += 4) {
        float xw = points[i], yw = points[i+1], zw = points[i+2];
        VoxelKey key{ static_cast<int>(std::floor(xw / mLastVoxelSize)), static_cast<int>(std::floor(yw / mLastVoxelSize)), static_cast<int>(std::floor(zw / mLastVoxelSize)) };

        if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
            if (mSplatData.size() < MAX_SPLATS) {
                Splat s{};
                s.x = xw; s.y = yw; s.z = zw;
                s.r = 1.0f; s.g = 1.0f; s.b = 1.0f; s.a = 0.3f;
                s.scale[0] = s.scale[1] = s.scale[2] = 0.02f;
                s.rot[3] = 1.0f;
                s.confidence = 0.6f;
                mSplatData.push_back(s);
                mVoxelGrid[key] = static_cast<int>(mSplatData.size() - 1);
            }
        }
    }
    mDataDirty = true;
}

void VoxelHash::addKeyframe(const Keyframe& kf) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mKeyframes.size() > 5) mKeyframes.erase(mKeyframes.begin());
    mKeyframes.push_back(kf);
}

void VoxelHash::optimize(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat) {
    if (depth.empty() || color.empty()) return;
    std::lock_guard<std::mutex> lock(mMutex);
    int splatCount = static_cast<int>(mSplatData.size());
    if (splatCount == 0) return;

    const Keyframe* kf = nullptr;
    if (!mKeyframes.empty()) { kf = &mKeyframes[rand() % mKeyframes.size()]; }

    glm::mat4 V = glm::make_mat4(viewMat);
    const float halfW = (float)depth.cols / 2.0f;
    const float halfH = (float)depth.rows / 2.0f;
    float fx = projMat[0] * halfW;
    float fy = projMat[5] * halfH;
    float cx = (projMat[8]  + 1.0f) * halfW;
    float cy = (-projMat[9] + 1.0f) * halfH;

    int batchSize = std::max(500, splatCount / 8);
    for (int i = 0; i < batchSize; ++i) {
        int idx = rand() % splatCount;
        if (mSplatData[idx].confidence >= 0.99f) continue;

        if (kf) {
            for(int j=0; j<3; ++j) mSplatData[idx].velocity[j] += (kf->linearVelocity[j] - mSplatData[idx].velocity[j]) * 0.05f;
        }

        glm::vec4 p_cam = V * glm::vec4(mSplatData[idx].x, mSplatData[idx].y, mSplatData[idx].z, 1.0f);
        if (p_cam.z >= -0.1f) continue;

        float u_cam = (p_cam.x * fx / -p_cam.z) + cx;
        float v_cam = (p_cam.y * -fy / -p_cam.z) + cy;

        if (u_cam >= 0 && u_cam < depth.cols && v_cam >= 0 && v_cam < depth.rows) {
            float d = depth.at<float>(static_cast<int>(v_cam), static_cast<int>(u_cam));
            if (d > 0.1f) {
                float err = std::abs(-p_cam.z - d);
                if (err < 0.15f) {
                    mSplatData[idx].confidence = std::min(1.0f, mSplatData[idx].confidence + 0.08f);
                    cv::Vec3b obs_col = color.at<cv::Vec3b>(static_cast<int>(v_cam), static_cast<int>(u_cam));
                    float r_obs = obs_col[2]/255.0f, g_obs = obs_col[1]/255.0f, b_obs = obs_col[0]/255.0f;
                    float alpha = 0.1f;
                    mSplatData[idx].r += (r_obs - mSplatData[idx].r) * alpha;
                    mSplatData[idx].g += (g_obs - mSplatData[idx].g) * alpha;
                    mSplatData[idx].b += (b_obs - mSplatData[idx].b) * alpha;

                    float dz_dx = (depth.at<float>(static_cast<int>(v_cam), std::min(depth.cols-1, static_cast<int>(u_cam)+1)) - depth.at<float>(static_cast<int>(v_cam), std::max(0, static_cast<int>(u_cam)-1))) / (2.0f / fx);
                    float dz_dy = (depth.at<float>(std::min(depth.rows-1, static_cast<int>(v_cam)+1), static_cast<int>(u_cam)) - depth.at<float>(std::max(0, static_cast<int>(v_cam)-1), static_cast<int>(u_cam))) / (2.0f / fy);
                    glm::vec3 normal = glm::normalize(glm::vec3(-dz_dx, -dz_dy, 1.0f));
                    glm::vec3 normalW = glm::normalize(glm::mat3(glm::inverse(V)) * normal);
                    glm::quat target_q = glm::rotation(glm::vec3(0, 0, 1), normalW);
                    glm::quat current_q(mSplatData[idx].rot[3], mSplatData[idx].rot[0], mSplatData[idx].rot[1], mSplatData[idx].rot[2]);
                    glm::quat refined_q = glm::slerp(current_q, target_q, 0.1f);
                    mSplatData[idx].rot[0] = refined_q.x; mSplatData[idx].rot[1] = refined_q.y; mSplatData[idx].rot[2] = refined_q.z; mSplatData[idx].rot[3] = refined_q.w;

                    if (mSplatData[idx].confidence > 0.8f && mSplatData[idx].scale[0] < 0.005f) {
                        mSplatData[idx].scale[0] *= 1.1f; mSplatData[idx].scale[1] *= 1.1f;
                    }
                } else { mSplatData[idx].confidence -= 0.04f; }
            }
        }
    }
    mDataDirty = true;
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
    glUniform1f(glGetUniformLocation(mProgram, "uTimeStep"), 0.016f);
    glm::vec3 camPos = glm::vec3(glm::inverse(view)[3]);
    glUniform3fv(glGetUniformLocation(mProgram, "uCamPos"), 1, glm::value_ptr(camPos));

    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE);
    glDepthMask(GL_FALSE);
    glDisable(GL_DEPTH_TEST);

    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(12));
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(64));
    glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(76));
    glEnableVertexAttribArray(4); glVertexAttribPointer(4, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(92));
    glEnableVertexAttribArray(5); glVertexAttribPointer(5, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(96));
    glEnableVertexAttribArray(6); glVertexAttribPointer(6, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(28));
    glEnableVertexAttribArray(7); glVertexAttribPointer(7, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(40));
    glEnableVertexAttribArray(8); glVertexAttribPointer(8, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(52));

    glDrawArrays(GL_POINTS, 0, count);

    glDisableVertexAttribArray(0); glDisableVertexAttribArray(1); glDisableVertexAttribArray(2); glDisableVertexAttribArray(3);
    glDisableVertexAttribArray(4); glDisableVertexAttribArray(5); glDisableVertexAttribArray(6); glDisableVertexAttribArray(7); glDisableVertexAttribArray(8);
    glDepthMask(GL_TRUE);
    glEnable(GL_DEPTH_TEST);
}

void VoxelHash::clear() {
    std::lock_guard<std::mutex> lock(mMutex);
    mSplatData.clear();
    mVoxelGrid.clear();
    mKeyframes.clear();
    mDataDirty = true;
}

void VoxelHash::pruneInternal(float threshold, float voxelSize) {
    mSplatData.erase(std::remove_if(mSplatData.begin(), mSplatData.end(),
        [threshold](const Splat& s) { return s.confidence < threshold; }), mSplatData.end());
    mVoxelGrid.clear();
    for (int i = 0; i < static_cast<int>(mSplatData.size()); ++i) {
        VoxelKey key{ static_cast<int>(std::floor(mSplatData[i].x / voxelSize)), static_cast<int>(std::floor(mSplatData[i].y / voxelSize)), static_cast<int>(std::floor(mSplatData[i].z / voxelSize)) };
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
