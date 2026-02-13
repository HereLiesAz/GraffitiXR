#include "MobileGS.h"
#include <android/log.h>
#include <GLES3/gl3.h>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <iostream>

#define LOG_TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Constants
const int SAMPLE_STRIDE = 4;
const float MIN_DEPTH = 0.2f;
const float MAX_DEPTH = 5.0f;
const int MAX_SPLATS = 500000;

// Shaders
const char* VERTEX_SHADER = R"(#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aColor;
layout(location = 2) in float aOpacity;

uniform mat4 uMVP;
uniform float uPointSize;

out vec4 vColor;

void main() {
    gl_Position = uMVP * vec4(aPosition, 1.0);
    gl_PointSize = uPointSize / gl_Position.w;
    vColor = vec4(aColor, aOpacity);
}
)";

const char* FRAGMENT_SHADER = R"(#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 FragColor;

void main() {
    if (length(gl_PointCoord - vec2(0.5)) > 0.5) discard;
    FragColor = vColor;
}
)";

// Helper: Compile Shader
GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            std::vector<char> infoLog(infoLen);
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog.data());
            LOGE("Error compiling shader:\n%s", infoLog.data());
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

// Helper: Create Program
GLuint createProgram(const char* vSource, const char* fSource) {
    GLuint vShader = compileShader(GL_VERTEX_SHADER, vSource);
    if (vShader == 0) return 0;

    GLuint fShader = compileShader(GL_FRAGMENT_SHADER, fSource);
    if (fShader == 0) {
        glDeleteShader(vShader);
        return 0;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vShader);
    glAttachShader(program, fShader);
    glLinkProgram(program);

    GLint linked;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            std::vector<char> infoLog(infoLen);
            glGetProgramInfoLog(program, infoLen, nullptr, infoLog.data());
            LOGE("Error linking program:\n%s", infoLog.data());
        }
        glDeleteProgram(program);
        program = 0;
    }

    glDeleteShader(vShader);
    glDeleteShader(fShader);

    return program;
}

// Matrix Mul Helper
void mat4_mul_vec3(const float* mat, float x, float y, float z, float& out_x, float& out_y, float& out_z) {
    out_x = mat[0] * x + mat[4] * y + mat[8] * z + mat[12];
    out_y = mat[1] * x + mat[5] * y + mat[9] * z + mat[13];
    out_z = mat[2] * x + mat[6] * y + mat[10] * z + mat[14];
}

MobileGS::MobileGS() : mFrameCount(0), mProgram(0), mLocMVP(-1), mLocPointSize(-1) {
    LOGI("MobileGS Constructor");
}

MobileGS::~MobileGS() {
    clear();
    if (mProgram != 0) {
        glDeleteProgram(mProgram);
        mProgram = 0;
    }
}

void MobileGS::initialize() {
    LOGI("MobileGS Initialize");
    if (mProgram == 0) {
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        mLocMVP = glGetUniformLocation(mProgram, "uMVP");
        mLocPointSize = glGetUniformLocation(mProgram, "uPointSize");
    }
}

void MobileGS::setTargetDescriptors(const cv::Mat& descriptors) {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    descriptors.copyTo(mTargetDescriptors);
    mHasTarget = !mTargetDescriptors.empty();
    LOGI("MobileGS: Target descriptors set. Rows: %d", mTargetDescriptors.rows);
}

/**
 * Prunes the map by removing splats that haven't been seen recently.
 * Prevents infinite memory growth.
 */
void MobileGS::pruneMap(int ageThresholdFrames) {
    std::lock_guard<std::mutex> lock(mChunkMutex);

    int totalRemoved = 0;

    // We can't prune frames newer than frameCount (obviously),
    // but we remove things where mFrameCount - lastSeen > threshold

    auto it = mChunks.begin();
    while (it != mChunks.end()) {
        Chunk& chunk = it->second;

        // Remove old splats using erase-remove idiom
        auto newEnd = std::remove_if(chunk.splats.begin(), chunk.splats.end(),
                [this, ageThresholdFrames](const Splat& s) {
                    return (this->mFrameCount - s.lastSeenFrame) > ageThresholdFrames;
                });

        int removedInChunk = std::distance(newEnd, chunk.splats.end());
        totalRemoved += removedInChunk;

        chunk.splats.erase(newEnd, chunk.splats.end());
        chunk.splatCount = chunk.splats.size();

        if (removedInChunk > 0) {
            chunk.isDirty = true;
        }

        // If chunk is empty, remove it
        if (chunk.splats.empty()) {
            if (chunk.vbo != 0) glDeleteBuffers(1, &chunk.vbo);
            it = mChunks.erase(it);
        } else {
            ++it;
        }
    }

    if (totalRemoved > 0) {
        LOGI("Pruned %d splats. Total chunks: %zu", totalRemoved, mChunks.size());
    }
}

/**
 * Projects depth pixels into 3D world space and adds them to the point cloud.
 */
void MobileGS::feedDepthData(const uint16_t* depthPixels, const uint8_t* colorPixels,
        int width, int height, int stride, const float* cameraPose, float fov) {
    if (!depthPixels) return;

    mFrameCount++;

    // Intrinsics
    float aspect = (float)width / (float)height;
    // fov is vertical fov in radians
    float fy = height / (2.0f * tan(fov / 2.0f));
    float fx = fy;
    float cx = width / 2.0f;
    float cy = height / 2.0f;

    std::vector<Splat> newSplats;
    int rowStrideShorts = stride / 2;

    for (int v = 0; v < height; v += SAMPLE_STRIDE) {
        for (int u = 0; u < width; u += SAMPLE_STRIDE) {
            uint16_t d_raw = depthPixels[v * rowStrideShorts + u];
            if (d_raw == 0) continue;

            float z_local = d_raw * 0.001f;
            if (z_local < MIN_DEPTH || z_local > MAX_DEPTH) continue;

            float x_local = (u - cx) * z_local / fx;
            float y_local = (v - cy) * z_local / fy;

            float x_world, y_world, z_world;
            mat4_mul_vec3(cameraPose, x_local, y_local, -z_local, x_world, y_world, z_world);

            Splat s;
            s.x = x_world;
            s.y = y_world;
            s.z = z_world;

            // Sample Color if available
            if (colorPixels) {
                // Assume color buffer is RGBA packed, row major, size width*height
                // Index = (v * width + u) * 4
                int cIdx = (v * width + u) * 4;
                s.r = colorPixels[cIdx];
                s.g = colorPixels[cIdx+1];
                s.b = colorPixels[cIdx+2];
            } else {
                s.r = 0; s.g = 200; s.b = 128; // Default Teal
            }

            s.confidence = 1.0f;
            s.radius = 0.02f;
            s.luminance = 0.5f;
            s.lastSeenFrame = mFrameCount;

            newSplats.push_back(s);
        }
    }

    // Add to chunk
    std::lock_guard<std::mutex> lock(mChunkMutex);
    for (const auto& s : newSplats) {
        ChunkKey key = getChunkKey(s.x, s.y, s.z);
        if (mChunks.find(key) == mChunks.end()) {
            mChunks[key] = Chunk();
        }

        Chunk& chunk = mChunks[key];
        // Only add if not overflowing (simple limit)
        if (chunk.splatCount < MAX_SPLATS / 10) {
            chunk.splats.push_back(s);
            chunk.splatCount++;
            chunk.isDirty = true;
        }
    }
}

void MobileGS::updateCamera(const float* view, const float* proj) {
    memcpy(&mStoredView[0][0], view, 16 * sizeof(float));
    memcpy(&mStoredProj[0][0], proj, 16 * sizeof(float));
}

void MobileGS::draw() {
    if (mProgram == 0) {
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        mLocMVP = glGetUniformLocation(mProgram, "uMVP");
        mLocPointSize = glGetUniformLocation(mProgram, "uPointSize");
    }

    glUseProgram(mProgram);

    glm::mat4 vp = mStoredProj * mStoredView;
    glUniformMatrix4fv(mLocMVP, 1, GL_FALSE, &vp[0][0]);
    glUniform1f(mLocPointSize, 15.0f); // Default point size

    std::lock_guard<std::mutex> lock(mChunkMutex);
    for (auto& pair : mChunks) {
        Chunk& chunk = pair.second;
        if (chunk.splats.empty()) continue;

        if (chunk.isDirty) {
            if (chunk.vbo == 0) glGenBuffers(1, &chunk.vbo);
            glBindBuffer(GL_ARRAY_BUFFER, chunk.vbo);

            std::vector<float> vboData;
            vboData.reserve(chunk.splats.size() * 7);
            for (const auto& s : chunk.splats) {
                vboData.push_back(s.x);
                vboData.push_back(s.y);
                vboData.push_back(s.z);
                vboData.push_back(s.r / 255.0f);
                vboData.push_back(s.g / 255.0f);
                vboData.push_back(s.b / 255.0f);
                vboData.push_back(1.0f); // Opacity
            }
            glBufferData(GL_ARRAY_BUFFER, vboData.size() * sizeof(float), vboData.data(), GL_STATIC_DRAW);
            chunk.isDirty = false;
        }

        glBindBuffer(GL_ARRAY_BUFFER, chunk.vbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)(3 * sizeof(float)));
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)(6 * sizeof(float)));

        glDrawArrays(GL_POINTS, 0, chunk.splats.size());

        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glDisableVertexAttribArray(2);
    }
}

void MobileGS::onSurfaceChanged(int width, int height) {
    mScreenWidth = width;
    mScreenHeight = height;
}

int MobileGS::getSplatCount() {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    int count = 0;
    for (const auto& pair : mChunks) {
        count += pair.second.splatCount;
    }
    return count;
}

void MobileGS::clear() {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    for (auto& pair : mChunks) {
        if (pair.second.vbo != 0) {
            glDeleteBuffers(1, &pair.second.vbo);
        }
    }
    mChunks.clear();
}

bool MobileGS::saveModel(std::string path) {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    std::ofstream outFile(path, std::ios::binary);
    if (!outFile.is_open()) return false;

    const char magic[] = "GXRM";
    outFile.write(magic, 4);

    int32_t version = 1;
    outFile.write(reinterpret_cast<const char*>(&version), sizeof(version));

    int32_t totalSplats = 0;
    for (const auto& pair : mChunks) {
        totalSplats += pair.second.splatCount;
    }
    outFile.write(reinterpret_cast<const char*>(&totalSplats), sizeof(totalSplats));

    for (const auto& pair : mChunks) {
        const Chunk& chunk = pair.second;
        if (!chunk.splats.empty()) {
            outFile.write(reinterpret_cast<const char*>(chunk.splats.data()), chunk.splats.size() * sizeof(Splat));
        }
    }
    outFile.close();
    return true;
}

bool MobileGS::loadModel(std::string path) {
    std::lock_guard<std::mutex> lock(mChunkMutex);

    for (auto& pair : mChunks) {
        if (pair.second.vbo != 0) glDeleteBuffers(1, &pair.second.vbo);
    }
    mChunks.clear();

    std::ifstream inFile(path, std::ios::binary);
    if (!inFile.is_open()) return false;

    char magic[4];
    inFile.read(magic, 4);
    if (strncmp(magic, "GXRM", 4) != 0) return false;

    int32_t version;
    inFile.read(reinterpret_cast<char*>(&version), sizeof(version));

    int32_t totalSplats;
    inFile.read(reinterpret_cast<char*>(&totalSplats), sizeof(totalSplats));

    const int BATCH_SIZE = 10000;
    std::vector<Splat> buffer(BATCH_SIZE);
    int splatsRead = 0;

    while (splatsRead < totalSplats) {
        int toRead = std::min(BATCH_SIZE, totalSplats - splatsRead);
        inFile.read(reinterpret_cast<char*>(buffer.data()), toRead * sizeof(Splat));

        for (int i = 0; i < toRead; ++i) {
            Splat& s = buffer[i];
            ChunkKey key = getChunkKey(s.x, s.y, s.z);
            if (mChunks.find(key) == mChunks.end()) mChunks[key] = Chunk();
            mChunks[key].splats.push_back(s);
            mChunks[key].splatCount++;
            mChunks[key].isDirty = true;
        }
        splatsRead += toRead;
    }
    inFile.close();
    return true;
}

void MobileGS::alignMap(const float* transform) {
    // Stub
}

ChunkKey MobileGS::getChunkKey(float x, float y, float z) {
    return ChunkKey{(int)(x/mChunkSize), (int)(y/mChunkSize), (int)(z/mChunkSize)};
}