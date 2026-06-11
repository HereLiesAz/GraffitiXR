#include "include/VoxelHash.h"
#include "include/NativeUtil.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <fstream>
#include <random>
#include <chrono>
#include <glm/gtc/matrix_inverse.hpp>
#include <glm/gtc/type_ptr.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MobileGS", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MobileGS", __VA_ARGS__)

// ~~~ Persistent Voxel Memory Engine (Pocket-Ready) ~~~

static const char* kVertexShader =
    "#version 300 es\n"
    "precision highp float;\n"
    "layout(location = 0) in vec3 aPosition;\n"
    "layout(location = 1) in vec4 aColor;\n"
    "layout(location = 2) in vec3 aNormal;\n"
    "layout(location = 3) in float aConfidence;\n"
    "uniform mat4 uMvp;\n"
    "uniform float uFocalY;\n"
    "uniform int uDebugTint;\n"
    "out vec4 vColor;\n"
    "void main() {\n"
    "    gl_Position = uMvp * vec4(aPosition, 1.0);\n"
    "    // Physical point size mapping: diameter in pixels\n"
    "    gl_PointSize = (0.02 * 1.5 * uFocalY) / gl_Position.w;\n"
    "    if (uDebugTint == 1) {\n"
    "        // Perception debug view: splats carry the CAMERA's colours, so rendered raw they\n"
    "        // reproduce the wall on the wall — perfectly camouflaged. Tint by confidence\n"
    "        // (cyan = tentative, magenta = locked) so the voxel map is visible AS a map.\n"
    "        vColor = vec4(mix(vec3(0.1, 0.9, 1.0), vec3(1.0, 0.2, 0.9), clamp(aConfidence, 0.0, 1.0)), 1.0);\n"
    "    } else {\n"
    "        vColor = aColor;\n"
    "    }\n"
    "}\n";

static const char* kFragmentShader =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec4 vColor;\n"
    "layout(location = 0) out vec4 oColor;\n"
    "void main() {\n"
    "    vec2 coord = gl_PointCoord - vec2(0.5);\n"
    "    if (length(coord) > 0.5) discard;\n"
    "    // Fast opaque output\n"
    "    oColor = vec4(vColor.rgb, 1.0);\n"
    "}\n";

VoxelHash::VoxelHash() : mProgram(0), mPointVbo(0), mDataDirty(false) {
    mSplatData.reserve(MAX_SPLATS);
    mRecentFrames.reserve(10);
    clear();
}

VoxelHash::~VoxelHash() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram) glDeleteProgram(mProgram);
    if (mPointVbo) glDeleteBuffers(1, &mPointVbo);
}

void VoxelHash::initGl() {
    initGlProgram();
    initGlBuffer();
}

void VoxelHash::initGlProgram() {
    // No mMutex here: mProgram is a GL handle, created/used/destroyed only on the GL thread. The
    // data mutex guards mSplatData/mSpatialHash (shared with worker threads), which this does not
    // touch. Locking it here only created contention with the heavy worker-held lock — the exact
    // stall that blacked out the camera (onSurfaceCreated never returned past this point).
    if (mProgram != 0 && glIsProgram(mProgram)) return;
    LOGI("Initializing Persistent Voxel Memory GL");

    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    LOGI("VoxelHash::initGlProgram vs compiled (%u)", vs);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    LOGI("VoxelHash::initGlProgram fs compiled (%u)", fs);
    mProgram = glCreateProgram();
    glAttachShader(mProgram, vs); glAttachShader(mProgram, fs);
    glLinkProgram(mProgram);
    LOGI("VoxelHash::initGlProgram program linked (%u)", mProgram);
    glDeleteShader(vs); glDeleteShader(fs);
}

void VoxelHash::initGlBuffer() {
    // No mMutex: mPointVbo is a GL handle, GL-thread-only (see initGlProgram).
    // glIsBuffer guards against re-allocating after a real GL-context reset re-creates handles.
    if (mPointVbo != 0 && glIsBuffer(mPointVbo)) return;
    glGenBuffers(1, &mPointVbo);
    glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
    LOGI("VoxelHash::initGlBuffer allocating %d splats", MAX_SPLATS);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(MAX_SPLATS * sizeof(Splat)), nullptr, GL_DYNAMIC_DRAW);
    LOGI("VoxelHash::initGlBuffer buffer allocated");
}

uint32_t VoxelHash::getVoxelHash(float x, float y, float z, float voxelSize) {
    int ix = static_cast<int>(std::floor(x / voxelSize));
    int iy = static_cast<int>(std::floor(y / voxelSize));
    int iz = static_cast<int>(std::floor(z / voxelSize));
    return static_cast<uint32_t>((ix * 73856093) ^ (iy * 19349663) ^ (iz * 83492791)) % HASH_SIZE;
}

void VoxelHash::update(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, float voxelSize, float initialConfidence) {
    if (depth.empty() || color.empty()) return;
    mLastVoxelSize = voxelSize;
    glm::mat4 invV = glm::inverse(glm::make_mat4(viewMat));
    const glm::vec3 camPos(invV[3]);  // camera world position, for parallax direction
    constexpr float kParallaxMinDeg = 4.0f; // a small sidestep qualifies; head-wobble does not

    float fx = projMat[0] * (depth.cols / 2.0f);
    float fy = projMat[5] * (depth.rows / 2.0f);
    float cx = (projMat[8] + 1.0f) * (depth.cols / 2.0f);
    float cy = (-projMat[9] + 1.0f) * (depth.rows / 2.0f);

    {
        std::lock_guard<std::mutex> lock(mMutex);
        static std::mt19937 gen(42);
        std::uniform_int_distribution<> disR(0, depth.rows - 1);
        std::uniform_int_distribution<> disC(0, depth.cols - 1);

        const float* depthPtr = (const float*)depth.data;
        const uint8_t* colorPtr = (const uint8_t*)color.data;
        size_t depthStep = depth.step1();
        size_t colorStep = color.step1();

        for (int i = 0; i < 2048; ++i) {
            int r = disR(gen); int c = disC(gen);
            float d = depthPtr[r * depthStep + c];
            if (d < 0.1f || d > 6.0f) continue;

            float xc = (static_cast<float>(c) - cx) * d / fx;
            float yc = -(static_cast<float>(r) - cy) * d / fy;
            glm::vec4 p_world = invV * glm::vec4(xc, yc, -d, 1.0f);

            uint32_t hash = getVoxelHash(p_world.x, p_world.y, p_world.z, voxelSize);
            int32_t existingIdx = mSpatialHash[hash];

            if (existingIdx == -1) {
                if (mSplatData.size() < MAX_SPLATS) {
                    Splat s{};
                    s.x = p_world.x; s.y = p_world.y; s.z = p_world.z;

                    int cr = r * color.rows / depth.rows;
                    int cc = c * color.cols / depth.cols;
                    const uint8_t* bgr = &colorPtr[cr * colorStep + cc * 3];
                    s.r = bgr[2]/255.0f; s.g = bgr[1]/255.0f; s.b = bgr[0]/255.0f; s.a = 1.0f;
                    s.confidence = initialConfidence;

                    int r_next = std::min(depth.rows-1, r+1);
                    int r_prev = std::max(0, r-1);
                    int c_next = std::min(depth.cols-1, c+1);
                    int c_prev = std::max(0, c-1);

                    float dz_dx = (depthPtr[r * depthStep + c_next] - depthPtr[r * depthStep + c_prev]) / (2.0f / fx);
                    float dz_dy = (depthPtr[r_next * depthStep + c] - depthPtr[r_prev * depthStep + c]) / (2.0f / fy);
                    glm::vec3 normalW = glm::normalize(glm::mat3(invV) * glm::normalize(glm::vec3(-dz_dx, -dz_dy, 1.0f)));
                    s.nx = normalW.x; s.ny = normalW.y; s.nz = normalW.z;

                    // First-observation direction (voxel→camera), for the later parallax check.
                    glm::vec3 dir0 = glm::normalize(camPos - glm::vec3(s.x, s.y, s.z));
                    s.ox = dir0.x; s.oy = dir0.y; s.oz = dir0.z;
                    s.parallaxDone = 0.0f;

                    mSpatialHash[hash] = static_cast<int32_t>(mSplatData.size());
                    mSplatData.push_back(s);
                    mDataDirty = true;
                }
            } else {
                Splat& s = mSplatData[existingIdx];
                // Parallax confidence: the FIRST time this voxel is re-observed from a viewpoint
                // clearly off the original line of sight (a real baseline, not head-wobble), use
                // the angular shift to verify depth. If the new depth reading lands the point at
                // the same sub-voxel place, the depth was correct → +0.1; if it disagrees within
                // the cell, the original depth was likely wrong → -0.1. Fires once per voxel.
                if (s.parallaxDone < 0.5f) {
                    glm::vec3 vpos(s.x, s.y, s.z);
                    glm::vec3 dirNow = glm::normalize(camPos - vpos);
                    glm::vec3 dir0(s.ox, s.oy, s.oz);
                    float c0 = glm::clamp(glm::dot(dir0, dirNow), -1.0f, 1.0f);
                    float angleDeg = glm::degrees(std::acos(c0));
                    if (angleDeg >= kParallaxMinDeg) {
                        float disagreement = glm::length(glm::vec3(p_world) - vpos);
                        if (disagreement < voxelSize * 0.5f) s.confidence = std::min(1.0f, s.confidence + 0.1f);
                        else                                  s.confidence = std::max(0.0f, s.confidence - 0.1f);
                        s.parallaxDone = 1.0f;
                        mDataDirty = true;
                    }
                }
                // Existing same-view reinforcement (unchanged): refine position, nudge confidence.
                if (s.confidence < 1.0f) {
                    float alpha = 1.0f / (s.confidence * 10.0f + 1.0f);
                    s.x += (p_world.x - s.x) * alpha;
                    s.y += (p_world.y - s.y) * alpha;
                    s.z += (p_world.z - s.z) * alpha;
                    s.confidence = std::min(1.0f, s.confidence + 0.05f);
                    mDataDirty = true;
                }
            }
        }
    }
}

void VoxelHash::addSparsePoints(const std::vector<float>& points, const float* viewMat, const float* projMat, float initialConfidence) {
    std::lock_guard<std::mutex> lock(mMutex);
    const glm::vec3 camPos(glm::inverse(glm::make_mat4(viewMat))[3]);
    for (size_t i = 0; i < points.size(); i += 4) {
        float xw = points[i], yw = points[i+1], zw = points[i+2];
        uint32_t hash = getVoxelHash(xw, yw, zw, mLastVoxelSize);
        if (mSpatialHash[hash] == -1 && mSplatData.size() < MAX_SPLATS) {
            Splat s{};
            s.x = xw; s.y = yw; s.z = zw;
            s.r = 1.0f; s.g = 1.0f; s.b = 1.0f; s.a = 1.0f;
            s.confidence = initialConfidence;
            glm::vec3 dir0 = glm::normalize(camPos - glm::vec3(xw, yw, zw));
            s.ox = dir0.x; s.oy = dir0.y; s.oz = dir0.z;
            s.parallaxDone = 0.0f;
            mSpatialHash[hash] = static_cast<int32_t>(mSplatData.size());
            mSplatData.push_back(s);
            mDataDirty = true;
        }
    }
}

void VoxelHash::draw(const glm::mat4& mvp, const glm::mat4& view, float focalY, int screenHeight, bool debugTint) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mProgram || mSplatData.empty()) return;

    auto now = std::chrono::steady_clock::now();
    int64_t nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();

    if (mDataDirty) {
        // Throttled upload: Only re-sync if 100ms passed OR > 1000 new points added
        if (nowMs - mLastUploadTimeMs > 100 || mSplatData.size() - mLastUploadCount > 1000) {
            glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
            glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(mSplatData.size() * sizeof(Splat)), mSplatData.data(), GL_DYNAMIC_DRAW);
            mDataDirty = false;
            mLastUploadCount = mSplatData.size();
            mLastUploadTimeMs = nowMs;
        }
    }

    glUseProgram(mProgram);
    glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uMvp"), 1, GL_FALSE, glm::value_ptr(mvp));
    glUniform1f(glGetUniformLocation(mProgram, "uFocalY"), focalY);
    glUniform1i(glGetUniformLocation(mProgram, "uDebugTint"), debugTint ? 1 : 0);

    if (debugTint) {
        // Debug view: splats draw on TOP, unoccluded — same as the ARCore feature dots, which are
        // visible precisely because they ignore depth. With depth test on and LEQUAL, the camera
        // background / artwork overlay already in the depth buffer cull the splats (they sit AT the
        // wall, the overlay sits in front). The point of the perception view is to SEE the map, not
        // to have it correctly occluded.
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDepthMask(GL_FALSE);
    } else {
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(GL_TRUE);
        glDepthFunc(GL_LEQUAL);
    }

    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)nullptr);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(12));
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(28));
    glEnableVertexAttribArray(3); glVertexAttribPointer(3, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(40));

    glDrawArrays(GL_POINTS, 0, static_cast<GLsizei>(mSplatData.size()));

    glDisableVertexAttribArray(0); glDisableVertexAttribArray(1); glDisableVertexAttribArray(2); glDisableVertexAttribArray(3);

    // Restore depth state the rest of the pipeline expects (the debug path disabled it).
    glEnable(GL_DEPTH_TEST);
    glDepthMask(GL_TRUE);
}

void VoxelHash::clear() {
    std::lock_guard<std::mutex> lock(mMutex);
    clearLocked();
}

void VoxelHash::clearLocked() {
    mSplatData.clear();
    mRecentFrames.clear();
    for (int i = 0; i < HASH_SIZE; ++i) mSpatialHash[i] = -1;
    mDataDirty = true;
}

void VoxelHash::save(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) return;
    // Magic + version precede the count so load() can tell the current Splat layout (with the
    // appended parallax fields) from legacy files written before them. Legacy files have no magic;
    // their first word is the count, which is < kMagic, so the two are unambiguous.
    const uint32_t magic = kSplatMagic;
    const uint32_t version = kSplatVersion;
    out.write(reinterpret_cast<const char*>(&magic), sizeof(uint32_t));
    out.write(reinterpret_cast<const char*>(&version), sizeof(uint32_t));
    uint32_t count = static_cast<uint32_t>(mSplatData.size());
    out.write(reinterpret_cast<const char*>(&count), sizeof(uint32_t));
    out.write(reinterpret_cast<const char*>(mSplatData.data()), static_cast<std::streamsize>(count * sizeof(Splat)));
}

void VoxelHash::load(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return;
    // clearLocked, NOT clear(): mMutex is non-recursive and already held here. Calling the
    // public clear() self-deadlocked this (IO) thread permanently holding mMutex; the frame-tick
    // coroutine then blocked on it inside MobileGS::getConfidenceAvgs while holding
    // MobileGS::mMutex, and the GL thread blocked on THAT in updateCamera ("step=slamCamera"
    // stall) — killing the render loop, starving ARCore's update(), and hard-freezing the app
    // on exit. Triggered on every AR entry for any project with a saved map.
    clearLocked();
    uint32_t first;
    if (!in.read(reinterpret_cast<char*>(&first), sizeof(uint32_t))) return;

    if (first == kSplatMagic) {
        // Current format: magic, version, count, then full Splats.
        uint32_t version = 0;
        in.read(reinterpret_cast<char*>(&version), sizeof(uint32_t));
        uint32_t count = 0;
        in.read(reinterpret_cast<char*>(&count), sizeof(uint32_t));
        if (count > MAX_SPLATS) count = MAX_SPLATS;
        mSplatData.resize(count);
        in.read(reinterpret_cast<char*>(mSplatData.data()), static_cast<std::streamsize>(count * sizeof(Splat)));
    } else {
        // Legacy format (no magic): `first` was the count, followed by the old 11-float Splat
        // (x,y,z, r,g,b,a, nx,ny,nz, confidence) with no parallax fields. Migrate field-by-field
        // so existing saved maps survive the struct change; parallax fields start unverified.
        uint32_t count = first;
        if (count > MAX_SPLATS) count = MAX_SPLATS;
        mSplatData.clear();
        mSplatData.reserve(count);
        for (uint32_t i = 0; i < count; ++i) {
            float f[11];
            if (!in.read(reinterpret_cast<char*>(f), sizeof(f))) break;
            Splat s{};
            s.x = f[0]; s.y = f[1]; s.z = f[2];
            s.r = f[3]; s.g = f[4]; s.b = f[5]; s.a = f[6];
            s.nx = f[7]; s.ny = f[8]; s.nz = f[9];
            s.confidence = f[10];
            s.ox = s.oy = s.oz = 0.0f;   // unknown first-view direction
            s.parallaxDone = 1.0f;       // don't retroactively parallax-check migrated voxels
            mSplatData.push_back(s);
        }
    }
    for (uint32_t i = 0; i < mSplatData.size(); ++i) {
        uint32_t hash = getVoxelHash(mSplatData[i].x, mSplatData[i].y, mSplatData[i].z, mLastVoxelSize);
        mSpatialHash[hash] = static_cast<int32_t>(i);
    }
    mDataDirty = true;
}

// Both counts MUST hold mMutex: the map thread mutates mSplatData under the lock, and an
// unlocked size()/iteration racing a push_back reallocation is a use-after-free read. These are
// polled every few frames from the UI-tick coroutine, so the race window was hit constantly.
int VoxelHash::getSplatCount() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return static_cast<int>(mSplatData.size());
}
int VoxelHash::getImmutableSplatCount() const {
    std::lock_guard<std::mutex> lock(mMutex);
    int c = 0;
    for (const auto& s : mSplatData) if (s.confidence >= 0.95f) c++;
    return c;
}

void VoxelHash::getAnchorCandidates(std::vector<Splat>& out, float threshold, int maxCount) const {
    std::lock_guard<std::mutex> lock(mMutex);
    out.clear();
    for (const auto& s : mSplatData) {
        if (s.confidence >= threshold) {
            out.push_back(s);
            if ((int)out.size() >= maxCount) break;
        }
    }
}

float VoxelHash::getGlobalConfidenceAvg() const {
    if (mSplatData.empty()) return 0.0f;
    double sum = 0.0;
    for (const auto& s : mSplatData) sum += s.confidence;
    return static_cast<float>(sum / mSplatData.size());
}
// TODO(B-followup): pass the current view/proj matrix to cull to the visible frustum.
float VoxelHash::getVisibleConfidenceAvg(const glm::mat4& mvp) const {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mSplatData.empty()) return 0.0f;
    double sum = 0.0;
    int count = 0;
    for (const auto& s : mSplatData) {
        glm::vec4 p = mvp * glm::vec4(s.x, s.y, s.z, 1.0f);
        if (p.w > 0) {
            float ndcX = p.x / p.w;
            float ndcY = p.y / p.w;
            if (ndcX >= -1.1f && ndcX <= 1.1f && ndcY >= -1.1f && ndcY <= 1.1f) {
                sum += s.confidence;
                count++;
            }
        }
    }
    return (count > 0) ? static_cast<float>(sum / count) : 0.0f;
}
void VoxelHash::addKeyframe(const VoxelFrame& kf) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mRecentFrames.size() > 5) mRecentFrames.erase(mRecentFrames.begin());
    mRecentFrames.push_back(kf);
}
void VoxelHash::prune(float threshold) {
    std::lock_guard<std::mutex> lock(mMutex);
    auto it = std::remove_if(mSplatData.begin(), mSplatData.end(), [threshold](const Splat& s) {
        return s.confidence < threshold;
    });
    if (it != mSplatData.end()) {
        mSplatData.erase(it, mSplatData.end());
        // Must rebuild the spatial hash indices because they refer to the vector positions
        for (int i = 0; i < HASH_SIZE; ++i) mSpatialHash[i] = -1;
        for (size_t i = 0; i < mSplatData.size(); ++i) {
            uint32_t hash = getVoxelHash(mSplatData[i].x, mSplatData[i].y, mSplatData[i].z, mLastVoxelSize);
            mSpatialHash[hash] = static_cast<int32_t>(i);
        }
        mDataDirty = true;
    }
}
void VoxelHash::optimize(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat) {}
void VoxelHash::sort(const glm::vec3& camPos) {}
