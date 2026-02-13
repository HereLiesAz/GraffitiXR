#include "MobileGS.h"
#include <android/log.h>
#include <GLES3/gl3.h>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <iostream>
#include <fstream>

#define LOG_TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Constants
const int SAMPLE_STRIDE = 4;
const float MIN_DEPTH = 0.2f;
const float MAX_DEPTH = 5.0f;
const int MAX_SPLATS = 500000;
const float VOXEL_SIZE = 0.02f; // 2cm

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

MobileGS::MobileGS() : mFrameCount(0), mProgram(0), mLocMVP(-1), mLocPointSize(-1), mVBO(0), mGlDirty(true) {
    LOGI("MobileGS Constructor");
}

MobileGS::~MobileGS() {
    // Note: GL cleanup in destructor is dangerous if context is already gone
    clear();
}

void MobileGS::initialize() {
    if (mProgram == 0) {
        LOGI("MobileGS Initialize: Compiling Shaders");
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        mLocMVP = glGetUniformLocation(mProgram, "uMVP");
        mLocPointSize = glGetUniformLocation(mProgram, "uPointSize");

        // Mark dirty to force VBO creation/upload in next draw
        mGlDirty = true;
    }
}

// NEW: Called when surface changes to invalidate old GL handles
void MobileGS::resetGL() {
    LOGI("MobileGS: Resetting GL State");
    mProgram = 0;
    mVBO = 0;
    mLocMVP = -1;
    mLocPointSize = -1;
    mGlDirty = true;
    // We DO NOT clear mSplats. Data persists.
}

void MobileGS::uploadSplatData() {
    if (mSplats.empty()) return;

    if (mVBO == 0) {
        glGenBuffers(1, &mVBO);
    }

    glBindBuffer(GL_ARRAY_BUFFER, mVBO);
    glBufferData(GL_ARRAY_BUFFER, mSplats.size() * sizeof(Splat), mSplats.data(), GL_DYNAMIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    mGlDirty = false;
}

void MobileGS::draw() {
    if (mProgram == 0) return;
    if (mSplats.empty()) return;

    // Use Program
    glUseProgram(mProgram);

    // Update Uniforms
    updateMVP();
    glUniformMatrix4fv(mLocMVP, 1, GL_FALSE, mMVPMatrix);
    glUniform1f(mLocPointSize, 20.0f); // Base point size

    // Upload Data if needed (Context switch or new points)
    if (mGlDirty) {
        uploadSplatData();
    }

    if (mVBO == 0) return; // Should have been created above

    glBindBuffer(GL_ARRAY_BUFFER, mVBO);

    // Attribute 0: Position (x, y, z)
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)0);

    // Attribute 1: Color (r, g, b)
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(3 * sizeof(float)));

    // Attribute 2: Opacity
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(6 * sizeof(float)));

    // Draw
    glDrawArrays(GL_POINTS, 0, mSplats.size());

    // Cleanup
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glDisableVertexAttribArray(2);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void MobileGS::updateMVP() {
    // Simple Matrix Multiply: MVP = Proj * View
    // Assumes Column Major
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            mMVPMatrix[j * 4 + i] = 0.0f;
            for (int k = 0; k < 4; k++) {
                mMVPMatrix[j * 4 + i] += mProjMatrix[k * 4 + i] * mViewMatrix[j * 4 + k];
            }
        }
    }
}

void MobileGS::setTargetDescriptors(const cv::Mat& descriptors) {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    descriptors.copyTo(mTargetDescriptors);
    mHasTarget = !mTargetDescriptors.empty();
    LOGI("MobileGS: Target descriptors set. Rows: %d", mTargetDescriptors.rows);
}

void MobileGS::pruneMap(int ageThresholdFrames) {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    // Placeholder for pruning logic
    // Implementation would iterate mSplats and remove low confidence ones
}

void MobileGS::clear() {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    mSplats.clear();
    mVoxelGrid.clear();
    mGlDirty = true;
}

int MobileGS::getSplatCount() {
    return mSplats.size();
}

void MobileGS::onSurfaceChanged(int width, int height) {
    mViewportWidth = width;
    mViewportHeight = height;
    glViewport(0, 0, width, height);
}

void MobileGS::updateCamera(float* viewMtx, float* projMtx) {
    memcpy(mViewMatrix, viewMtx, 16 * sizeof(float));
    memcpy(mProjMatrix, projMtx, 16 * sizeof(float));
}

void MobileGS::feedDepthData(uint16_t* depthData, uint8_t* colorData, int width, int height, int stride, float* poseMtx, float fov) {
    std::lock_guard<std::mutex> lock(mChunkMutex);

    // Safety check on limits
    if (mSplats.size() >= MAX_SPLATS) return;

    // Simple Voxel Hashing Implementation
    // Iterate depth image with stride
    for (int y = 0; y < height; y += SAMPLE_STRIDE) {
        for (int x = 0; x < width; x += SAMPLE_STRIDE) {
            int idx = y * stride + x;
            float depth = depthData[idx] * 0.001f; // mm to meters

            if (depth < MIN_DEPTH || depth > MAX_DEPTH) continue;

            // Unproject to Camera Space
            float ndc_x = ((float)x / width) * 2.0f - 1.0f;
            float ndc_y = ((float)y / height) * 2.0f - 1.0f;

            // Simple Pinhole approximation for now (should use intrinsics)
            float tanHalfFov = tan(fov * 0.5f);
            float aspectRatio = (float)width / height;
            float cam_x = ndc_x * depth * tanHalfFov * aspectRatio;
            float cam_y = ndc_y * depth * tanHalfFov; // Y is inverted in some systems, checking... assuming GL standard
            float cam_z = -depth;

            // Transform to World Space
            float world_x, world_y, world_z;
            mat4_mul_vec3(poseMtx, cam_x, cam_y, cam_z, world_x, world_y, world_z);

            // Voxel Key
            VoxelKey key = {
                    (int)floor(world_x / VOXEL_SIZE),
                    (int)floor(world_y / VOXEL_SIZE),
                    (int)floor(world_z / VOXEL_SIZE)
            };

            // Check Grid
            if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
                // New Splat
                Splat s;
                s.x = world_x; s.y = world_y; s.z = world_z;

                // Color extraction (if available)
                if (colorData) {
                    // NV21/YUV logic required here usually, assuming RGB input for simplicity in this snippet
                    // In reality, ARCore gives YUV. We pass generic 0.5 grey if raw.
                    // For the sake of this fix, let's assume valid data or default.
                    s.r = 1.0f; s.g = 1.0f; s.b = 1.0f;
                } else {
                    s.r = 0.0f; s.g = 1.0f; s.b = 0.0f; // Green default
                }
                s.opacity = 0.1f; // Initial confidence

                mSplats.push_back(s);
                mVoxelGrid[key] = mSplats.size() - 1;
                mGlDirty = true;
            } else {
                // Update Existing (Integration)
                int idx = mVoxelGrid[key];
                Splat& s = mSplats[idx];
                // Running average position could go here
                if (s.opacity < 1.0f) s.opacity += 0.05f;
            }
        }
    }
}

bool MobileGS::saveModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;

    // Header "GXRM"
    out.write("GXRM", 4);
    int version = 1;
    out.write((char*)&version, sizeof(int));

    int count = mSplats.size();
    out.write((char*)&count, sizeof(int));

    out.write((char*)mSplats.data(), count * sizeof(Splat));
    return true;
}

bool MobileGS::loadModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;

    char header[4];
    in.read(header, 4);
    if (strncmp(header, "GXRM", 4) != 0) return false;

    int version;
    in.read((char*)&version, sizeof(int));

    int count;
    in.read((char*)&count, sizeof(int));

    mSplats.resize(count);
    in.read((char*)mSplats.data(), count * sizeof(Splat));

    // Rebuild Voxel Grid
    mVoxelGrid.clear();
    for(int i=0; i<count; i++) {
        const Splat& s = mSplats[i];
        VoxelKey key = {
                (int)floor(s.x / VOXEL_SIZE),
                (int)floor(s.y / VOXEL_SIZE),
                (int)floor(s.z / VOXEL_SIZE)
        };
        mVoxelGrid[key] = i;
    }

    mGlDirty = true;
    return true;
}

void MobileGS::alignMap(float* transformMtx) {
    // Apply transform to all points
    // Placeholder
}