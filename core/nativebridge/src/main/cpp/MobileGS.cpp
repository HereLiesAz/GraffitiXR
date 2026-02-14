#include "MobileGS.h"
#include <android/log.h>
#include <GLES3/gl3.h>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <iostream>
#include <fstream>
#include <cstddef>
#include <glm/gtc/type_ptr.hpp>

#define LOG_TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Constants
const int SAMPLE_STRIDE = 4;
const float MIN_DEPTH = 0.2f;
const float MAX_DEPTH = 5.0f;
const int MAX_SPLATS = 500000;
const float VOXEL_SIZE = 0.02f; // 2cm

const float QUAD_VERTICES[] = {
    -1.0f, -1.0f,
     1.0f, -1.0f,
    -1.0f,  1.0f,
     1.0f,  1.0f
};

// Shaders
// 3D Gaussian Splatting Vertex Shader
// Renders a 3D scaled and rotated quad
const char* VERTEX_SHADER = R"(#version 300 es
layout(location = 0) in vec2 aQuadVert; // Unit quad vertex (Attribute 0 - Divisor 0)
layout(location = 1) in vec3 aPosition; // Instance pos (Attribute 1 - Divisor 1)
layout(location = 2) in vec3 aScale;    // Instance scale (Attribute 2 - Divisor 1)
layout(location = 3) in vec4 aRotation; // Instance rot (Attribute 3 - Divisor 1)
layout(location = 4) in vec3 aColor;    // Instance color (Attribute 4 - Divisor 1)
layout(location = 5) in float aOpacity; // Instance opacity (Attribute 5 - Divisor 1)

uniform mat4 uView;
uniform mat4 uProj;

out vec4 vColor;
out vec2 vUV;

// Helper: Rotate vector by quaternion
vec3 rotateVector(vec3 v, vec4 q) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

void main() {
    // 1. Scale
    // We multiply by 3.0 to cover +/- 3 sigma (99.7% of energy)
    vec3 scaledVert = vec3(aQuadVert, 0.0) * aScale * 3.0;

    // 2. Rotate
    vec3 rotatedVert = rotateVector(scaledVert, aRotation);

    // 3. Translate
    vec3 worldPos = aPosition + rotatedVert;

    // 4. Project
    gl_Position = uProj * uView * vec4(worldPos, 1.0);

    vColor = vec4(aColor, aOpacity);
    vUV = aQuadVert * 3.0; // Pass scaled UVs for gaussian calc
}
)";

const char* FRAGMENT_SHADER = R"(#version 300 es
precision mediump float;
in vec4 vColor;
in vec2 vUV;
out vec4 FragColor;
void main() {
    // Gaussian Falloff
    // alpha = exp(-0.5 * (x^2 + y^2))
    float distSq = dot(vUV, vUV);
    float alpha = exp(-0.5 * distSq);

    // Hard cutoff at 3 sigma (which is u=3, distSq=9)
    if (distSq > 9.0 || alpha < 0.01) discard;

    FragColor = vec4(vColor.rgb, vColor.a * alpha);
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

MobileGS::MobileGS() : mFrameCount(0), mProgram(0), mLocView(-1), mLocProj(-1), mVBO_Quad(0), mVBO_Instance(0), mGlDirty(true) {
    LOGI("MobileGS Constructor");
}

MobileGS::~MobileGS() {
    clear();
}

void MobileGS::initialize() {
    if (mProgram == 0) {
        LOGI("MobileGS Initialize: Compiling Shaders");
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        mLocView = glGetUniformLocation(mProgram, "uView");
        mLocProj = glGetUniformLocation(mProgram, "uProj");

        // Mark dirty
        mGlDirty = true;
    }
}

void MobileGS::resetGL() {
    LOGI("MobileGS: Resetting GL State");
    mProgram = 0;
    mVBO_Quad = 0;
    mVBO_Instance = 0;
    mLocView = -1;
    mLocProj = -1;
    mGlDirty = true;
}

void MobileGS::uploadSplatData() {
    // 1. Setup Quad VBO (Once)
    if (mVBO_Quad == 0) {
        glGenBuffers(1, &mVBO_Quad);
        glBindBuffer(GL_ARRAY_BUFFER, mVBO_Quad);
        glBufferData(GL_ARRAY_BUFFER, sizeof(QUAD_VERTICES), QUAD_VERTICES, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    if (mSplats.empty()) return;

    // 2. Setup Instance VBO
    if (mVBO_Instance == 0) {
        glGenBuffers(1, &mVBO_Instance);
    }

    // Sort before upload (Painter's Algorithm for Alpha)
    sortSplats();

    glBindBuffer(GL_ARRAY_BUFFER, mVBO_Instance);
    glBufferData(GL_ARRAY_BUFFER, mSplats.size() * sizeof(Splat), mSplats.data(), GL_DYNAMIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    mGlDirty = false;
}

void MobileGS::draw() {
    if (mProgram == 0 || mSplats.empty()) return;

    glUseProgram(mProgram);

    // Update Uniforms
    glUniformMatrix4fv(mLocView, 1, GL_FALSE, glm::value_ptr(mViewMat));
    glUniformMatrix4fv(mLocProj, 1, GL_FALSE, glm::value_ptr(mProjMat));

    // Ensure data is on GPU (Sorted and Uploaded)
    // Note: Re-uploading every frame to handle sorting.
    uploadSplatData();

    if (mVBO_Quad == 0 || mVBO_Instance == 0) return;

    // --- Vertex Specification ---

    // 1. Quad Vertices (Attribute 0) - Per Vertex
    glBindBuffer(GL_ARRAY_BUFFER, mVBO_Quad);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, (void*)0);
    glVertexAttribDivisor(0, 0);

    // 2. Instance Attributes - Per Instance
    glBindBuffer(GL_ARRAY_BUFFER, mVBO_Instance);
    size_t stride = sizeof(Splat);

    // Helper lambda for cleanliness
    auto setAttrib = [&](int loc, int size, int type, bool norm, size_t offset) {
        glEnableVertexAttribArray(loc);
        glVertexAttribPointer(loc, size, type, norm, stride, (void*)offset);
        glVertexAttribDivisor(loc, 1);
    };

    setAttrib(1, 3, GL_FLOAT, GL_FALSE, offsetof(Splat, pos));
    setAttrib(2, 3, GL_FLOAT, GL_FALSE, offsetof(Splat, scale));
    setAttrib(3, 4, GL_FLOAT, GL_FALSE, offsetof(Splat, rot));
    setAttrib(4, 3, GL_FLOAT, GL_FALSE, offsetof(Splat, color));
    setAttrib(5, 1, GL_FLOAT, GL_FALSE, offsetof(Splat, opacity));

    // Draw Instanced
    glEnable(GL_DEPTH_TEST); // ENABLE DEPTH TEST
    glDepthMask(GL_TRUE); // Write to Depth Buffer

    glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, mSplats.size());

    glDisable(GL_DEPTH_TEST); // Cleanup
    glDepthMask(GL_FALSE);

    // Cleanup
    for(int i=0; i<=5; i++) {
        glDisableVertexAttribArray(i);
        glVertexAttribDivisor(i, 0); // Reset divisor
    }
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void MobileGS::sortSplats() {
    // Sort Back-to-Front (Farthest first) for alpha blending
    // Using distance squared to camera position
    std::sort(mSplats.begin(), mSplats.end(), [this](const Splat& a, const Splat& b) {
        glm::vec3 diff1 = a.pos - mCamPos;
        glm::vec3 diff2 = b.pos - mCamPos;
        float d1 = glm::dot(diff1, diff1);
        float d2 = glm::dot(diff2, diff2);
        return d1 > d2;
    });
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
    mViewMat = glm::make_mat4(viewMtx);
    mProjMat = glm::make_mat4(projMtx);

    // Extract Camera Position (Inverse of View Matrix)
    // View = R * T. InvView = T^-1 * R^-1.
    // Easier: glm::inverse(mViewMat)[3] gives the translation component of inverse, which is cam pos.
    glm::mat4 invView = glm::inverse(mViewMat);
    mCamPos = glm::vec3(invView[3]);
}

void MobileGS::feedDepthData(uint16_t* depthData, uint8_t* colorData, int width, int height, int depthStride, int colorStride, float* poseMtx, float fov) {
    std::lock_guard<std::mutex> lock(mChunkMutex);

    if (mSplats.size() >= MAX_SPLATS) return;

    glm::mat4 poseMat = glm::make_mat4(poseMtx);
    float tanHalfFov = tan(fov * 0.5f);
    float aspectRatio = (float)width / height;

    for (int y = 0; y < height; y += SAMPLE_STRIDE) {
        for (int x = 0; x < width; x += SAMPLE_STRIDE) {
            int idx = y * depthStride + x;
            float depth = depthData[idx] * 0.001f; // mm to meters

            if (depth < MIN_DEPTH || depth > MAX_DEPTH) continue;

            // 1. Unproject
            float ndc_x = ((float)x / width) * 2.0f - 1.0f;
            float ndc_y = ((float)y / height) * 2.0f - 1.0f;

            // Camera Space (Assuming OpenGL Coordinate System: -Z forward, +Y up)
            float cam_x = ndc_x * depth * tanHalfFov * aspectRatio;
            float cam_y = ndc_y * depth * tanHalfFov;
            float cam_z = -depth;

            glm::vec4 camPos(cam_x, cam_y, cam_z, 1.0f);
            glm::vec4 worldPos = poseMat * camPos;

            // 2. Voxel Hashing
            VoxelKey key = {
                    (int)floor(worldPos.x / VOXEL_SIZE),
                    (int)floor(worldPos.y / VOXEL_SIZE),
                    (int)floor(worldPos.z / VOXEL_SIZE)
            };

            if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
                // New Splat
                Splat s;
                s.pos = glm::vec3(worldPos);

                // Scale initialization
                float pixelSize = 2.0f * depth * tanHalfFov / height;
                float splatScale = pixelSize * SAMPLE_STRIDE * 1.5f;
                s.scale = glm::vec3(splatScale);

                // Rotation: Identity for now
                s.rot = glm::quat(1.0f, 0.0f, 0.0f, 0.0f);

                // Color
                if (colorData) {
                    // Assuming RGBA buffer from JNI
                    int pIdx = (y * colorStride) + (x * 4);
                    s.color = glm::vec3(
                        colorData[pIdx] / 255.0f,
                        colorData[pIdx + 1] / 255.0f,
                        colorData[pIdx + 2] / 255.0f
                    );
                } else {
                    s.color = glm::vec3(0.0f, 1.0f, 0.0f);
                }
                s.opacity = 0.5f;

                mSplats.push_back(s);
                mVoxelGrid[key] = mSplats.size() - 1;
                mGlDirty = true;
            } else {
                // Update Existing
                int splatIdx = mVoxelGrid[key];
                Splat& s = mSplats[splatIdx];

                // Moving Average for Position (Densification/Refinement)
                s.pos = glm::mix(s.pos, glm::vec3(worldPos), 0.1f);

                // Update Color
                if (colorData) {
                    int pIdx = (y * colorStride) + (x * 4);
                    glm::vec3 newColor(
                        colorData[pIdx] / 255.0f,
                        colorData[pIdx + 1] / 255.0f,
                        colorData[pIdx + 2] / 255.0f
                    );
                    s.color = glm::mix(s.color, newColor, 0.1f);
                }

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
    int version = 2; // Bump to v2 for new Splat struct
    out.write((char*)&version, sizeof(int));

    int count = mSplats.size();
    out.write((char*)&count, sizeof(int));

    out.write((char*)mSplats.data(), count * sizeof(Splat));
    return true;
}

// Backward compatibility struct
struct SplatV1 {
    float x, y, z;
    float r, g, b;
    float opacity;
};

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

    if (count < 0 || count > MAX_SPLATS) {
        LOGE("Invalid splat count in model file: %d", count);
        return false;
    }

    if (version == 1) {
        // Upgrade from V1
        std::vector<SplatV1> oldSplats(count);
        in.read((char*)oldSplats.data(), count * sizeof(SplatV1));

        mSplats.clear();
        mSplats.reserve(count);
        for(const auto& os : oldSplats) {
            Splat s;
            s.pos = glm::vec3(os.x, os.y, os.z);
            s.color = glm::vec3(os.r, os.g, os.b);
            s.opacity = os.opacity;
            // Defaults
            s.scale = glm::vec3(0.05f); // 5cm default
            s.rot = glm::quat(1.0f, 0.0f, 0.0f, 0.0f);
            mSplats.push_back(s);
        }
    } else if (version == 2) {
        mSplats.resize(count);
        in.read((char*)mSplats.data(), count * sizeof(Splat));
    } else {
        LOGE("Unknown map version: %d", version);
        return false;
    }

    // Rebuild Voxel Grid
    mVoxelGrid.clear();
    for(int i=0; i<mSplats.size(); i++) {
        const Splat& s = mSplats[i];
        VoxelKey key = {
                (int)floor(s.pos.x / VOXEL_SIZE),
                (int)floor(s.pos.y / VOXEL_SIZE),
                (int)floor(s.pos.z / VOXEL_SIZE)
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