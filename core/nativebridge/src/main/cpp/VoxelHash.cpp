#include "include/VoxelHash.h"
#include "include/NativeUtil.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <fstream>
#include <random>
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
    "out vec4 vColor;\n"
    "void main() {\n"
    "    gl_Position = uMvp * vec4(aPosition, 1.0);\n"
    "    // Physical point size mapping: diameter in pixels\n"
    "    gl_PointSize = (0.02 * 1.5 * uFocalY) / gl_Position.w;\n"
    "    vColor = aColor;\n"
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
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram != 0 && glIsProgram(mProgram)) return;
    LOGI("Initializing Persistent Voxel Memory GL");

    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    mProgram = glCreateProgram();
    glAttachShader(mProgram, vs); glAttachShader(mProgram, fs);
    glLinkProgram(mProgram);
    glDeleteShader(vs); glDeleteShader(fs);

    glGenBuffers(1, &mPointVbo);
    glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(MAX_SPLATS * sizeof(Splat)), nullptr, GL_DYNAMIC_DRAW);
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

    float fx = projMat[0] * (depth.cols / 2.0f);
    float fy = projMat[5] * (depth.rows / 2.0f);
    float cx = (projMat[8] + 1.0f) * (depth.cols / 2.0f);
    float cy = (-projMat[9] + 1.0f) * (depth.rows / 2.0f);

    {
        std::lock_guard<std::mutex> lock(mMutex);
        static std::mt19937 gen(42);
        std::uniform_int_distribution<> disR(0, depth.rows - 1);
        std::uniform_int_distribution<> disC(0, depth.cols - 1);

        for (int i = 0; i < 2048; ++i) {
            int r = disR(gen); int c = disC(gen);
            float d = depth.at<float>(r, c);
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
                    cv::Vec3b col = color.at<cv::Vec3b>(r * color.rows / depth.rows, c * color.cols / depth.cols);
                    s.r = col[0]/255.0f; s.g = col[1]/255.0f; s.b = col[2]/255.0f; s.a = 1.0f;
                    s.confidence = initialConfidence;

                    float dz_dx = (depth.at<float>(r, std::min(depth.cols-1, c+1)) - depth.at<float>(r, std::max(0, c-1))) / (2.0f / fx);
                    float dz_dy = (depth.at<float>(std::min(depth.rows-1, r+1), c) - depth.at<float>(std::max(0, r-1), c)) / (2.0f / fy);
                    glm::vec3 normalW = glm::normalize(glm::mat3(invV) * glm::normalize(glm::vec3(-dz_dx, -dz_dy, 1.0f)));
                    s.nx = normalW.x; s.ny = normalW.y; s.nz = normalW.z;

                    mSpatialHash[hash] = static_cast<int32_t>(mSplatData.size());
                    mSplatData.push_back(s);
                    mDataDirty = true;
                }
            } else {
                Splat& s = mSplatData[existingIdx];
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
    for (size_t i = 0; i < points.size(); i += 4) {
        float xw = points[i], yw = points[i+1], zw = points[i+2];
        uint32_t hash = getVoxelHash(xw, yw, zw, mLastVoxelSize);
        if (mSpatialHash[hash] == -1 && mSplatData.size() < MAX_SPLATS) {
            Splat s{};
            s.x = xw; s.y = yw; s.z = zw;
            s.r = 1.0f; s.g = 1.0f; s.b = 1.0f; s.a = 1.0f;
            s.confidence = initialConfidence;
            mSpatialHash[hash] = static_cast<int32_t>(mSplatData.size());
            mSplatData.push_back(s);
            mDataDirty = true;
        }
    }
}

void VoxelHash::draw(const glm::mat4& mvp, const glm::mat4& view, float focalY, int screenHeight) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mProgram || mSplatData.empty()) return;

    if (mDataDirty) {
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(mSplatData.size() * sizeof(Splat)), mSplatData.data(), GL_DYNAMIC_DRAW);
        mDataDirty = false;
    }

    glUseProgram(mProgram);
    glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uMvp"), 1, GL_FALSE, glm::value_ptr(mvp));
    glUniform1f(glGetUniformLocation(mProgram, "uFocalY"), focalY);

    glDisable(GL_BLEND);
    glEnable(GL_DEPTH_TEST);
    glDepthMask(GL_TRUE);
    glDepthFunc(GL_LEQUAL);

    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)nullptr);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(12));
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(28));
    glEnableVertexAttribArray(3); glVertexAttribPointer(3, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(40));

    glDrawArrays(GL_POINTS, 0, static_cast<GLsizei>(mSplatData.size()));

    glDisableVertexAttribArray(0); glDisableVertexAttribArray(1); glDisableVertexAttribArray(2); glDisableVertexAttribArray(3);
}

void VoxelHash::clear() {
    std::lock_guard<std::mutex> lock(mMutex);
    mSplatData.clear();
    mRecentFrames.clear();
    for (int i = 0; i < HASH_SIZE; ++i) mSpatialHash[i] = -1;
    mDataDirty = true;
}

void VoxelHash::save(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) return;
    uint32_t count = static_cast<uint32_t>(mSplatData.size());
    out.write(reinterpret_cast<const char*>(&count), sizeof(uint32_t));
    out.write(reinterpret_cast<const char*>(mSplatData.data()), static_cast<std::streamsize>(count * sizeof(Splat)));
}

void VoxelHash::load(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return;
    clear();
    uint32_t count;
    in.read(reinterpret_cast<char*>(&count), sizeof(uint32_t));
    if (count > MAX_SPLATS) count = MAX_SPLATS;
    mSplatData.resize(count);
    in.read(reinterpret_cast<char*>(mSplatData.data()), static_cast<std::streamsize>(count * sizeof(Splat)));
    for (uint32_t i = 0; i < count; ++i) {
        uint32_t hash = getVoxelHash(mSplatData[i].x, mSplatData[i].y, mSplatData[i].z, mLastVoxelSize);
        mSpatialHash[hash] = static_cast<int32_t>(i);
    }
    mDataDirty = true;
}

int VoxelHash::getSplatCount() const { return static_cast<int>(mSplatData.size()); }
int VoxelHash::getImmutableSplatCount() const {
    int c = 0;
    for (const auto& s : mSplatData) if (s.confidence >= 0.95f) c++;
    return c;
}
float VoxelHash::getVisibleConfidenceAvg() const { return 0.5f; }
float VoxelHash::getGlobalConfidenceAvg() const { return 0.5f; }
void VoxelHash::addKeyframe(const VoxelFrame& kf) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mRecentFrames.size() > 5) mRecentFrames.erase(mRecentFrames.begin());
    mRecentFrames.push_back(kf);
}
void VoxelHash::prune(float threshold) {}
void VoxelHash::optimize(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat) {}
void VoxelHash::sort(const glm::vec3& camPos) {}
