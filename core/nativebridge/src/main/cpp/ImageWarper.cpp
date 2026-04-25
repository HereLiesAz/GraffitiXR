#include "include/ImageWarper.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <glm/gtc/type_ptr.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ImageWarper", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ImageWarper", __VA_ARGS__)

static const char* kVertexShader =
    "#version 300 es\n"
    "layout(location = 0) in vec2 aPosition;\n"
    "layout(location = 1) in vec2 aTexCoord;\n"
    "out vec2 vTexCoord;\n"
    "void main() {\n"
    "    gl_Position = vec4(aPosition * 2.0 - 1.0, 0.0, 1.0);\n"
    "    vTexCoord = aTexCoord;\n"
    "}\n";

static const char* kFragmentShader =
    "#version 300 es\n"
    "precision highp float;\n"
    "uniform sampler2D uTexture;\n"
    "in vec2 vTexCoord;\n"
    "out vec4 oColor;\n"
    "void main() {\n"
    "    oColor = texture(uTexture, vTexCoord);\n"
    "}\n";

ImageWarper::ImageWarper() = default;

ImageWarper::~ImageWarper() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram) glDeleteProgram(mProgram);
    if (mTextureId) glDeleteTextures(1, &mTextureId);
    if (mVboPos) glDeleteBuffers(1, &mVboPos);
    if (mVboTex) glDeleteBuffers(1, &mVboTex);
    if (mIbo) glDeleteBuffers(1, &mIbo);
    if (mFbo) glDeleteFramebuffers(1, &mFbo);
    if (mOffscreenTexture) glDeleteTextures(1, &mOffscreenTexture);
}

void ImageWarper::init() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mInitialized) return;
    initShaders();
    mInitialized = true;
}

void ImageWarper::initShaders() {
    auto compileShader = [](GLenum type, const char* source) -> GLuint {
        GLuint s = glCreateShader(type);
        glShaderSource(s, 1, &source, nullptr);
        glCompileShader(s);
        GLint status;
        glGetShaderiv(s, GL_COMPILE_STATUS, &status);
        if (!status) {
            char buf[512];
            glGetShaderInfoLog(s, 512, nullptr, buf);
            LOGE("Shader compile error: %s", buf);
            return 0;
        }
        return s;
    };

    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    mProgram = glCreateProgram();
    glAttachShader(mProgram, vs);
    glAttachShader(mProgram, fs);
    glLinkProgram(mProgram);
    glDeleteShader(vs);
    glDeleteShader(fs);

    glGenBuffers(1, &mVboPos);
    glGenBuffers(1, &mVboTex);
    glGenBuffers(1, &mIbo);

    // Initialize Grid
    mVertices.clear();
    mTexCoords.clear();
    mIndices.clear();

    for (int y = 0; y <= mGridDim; ++y) {
        float fy = (float)y / (float)mGridDim;
        for (int x = 0; x <= mGridDim; ++x) {
            float fx = (float)x / (float)mGridDim;
            mVertices.emplace_back(fx, fy);
            mTexCoords.emplace_back(fx, fy);
        }
    }

    for (int y = 0; y < mGridDim; ++y) {
        for (int x = 0; x < mGridDim; ++x) {
            int i = y * (mGridDim + 1) + x;
            mIndices.push_back(i);
            mIndices.push_back(i + 1);
            mIndices.push_back(i + (mGridDim + 1));
            mIndices.push_back(i + 1);
            mIndices.push_back(i + (mGridDim + 1) + 1);
            mIndices.push_back(i + (mGridDim + 1));
        }
    }

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIbo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, (GLsizeiptr)(mIndices.size() * sizeof(uint32_t)), mIndices.data(), GL_STATIC_DRAW);

    glBindBuffer(GL_ARRAY_BUFFER, mVboTex);
    glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)(mTexCoords.size() * sizeof(glm::vec2)), mTexCoords.data(), GL_STATIC_DRAW);
}

void ImageWarper::setSourceImage(const uint8_t* data, int width, int height) {
    std::lock_guard<std::mutex> lock(mMutex);
    mWidth = width;
    mHeight = height;

    if (!mTextureId) glGenTextures(1, &mTextureId);
    glBindTexture(GL_TEXTURE_2D, mTextureId);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // Setup FBO for baking
    if (!mFbo) glGenFramebuffers(1, &mFbo);
    if (!mOffscreenTexture) glGenTextures(1, &mOffscreenTexture);
    glBindTexture(GL_TEXTURE_2D, mOffscreenTexture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glBindFramebuffer(GL_FRAMEBUFFER, mFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mOffscreenTexture, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    reset();
}

void ImageWarper::reset() {
    mVertices.clear();
    for (int y = 0; y <= mGridDim; ++y) {
        float fy = (float)y / mGridDim;
        for (int x = 0; x <= mGridDim; ++x) {
            float fx = (float)x / mGridDim;
            mVertices.push_back({fx, fy});
        }
    }
    mMeshDirty = true;
}

void ImageWarper::applyLiquify(const std::vector<glm::vec2>& stroke, float brushSize, float intensity) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (stroke.size() < 2) return;

    // Convert brush size to normalized [0, 1] relative to the image
    float normBrushSize = brushSize / std::max(mWidth, mHeight);

    // We iterate through the stroke segment
    for (size_t s = 1; s < stroke.size(); ++s) {
        glm::vec2 p1 = stroke[s-1];
        glm::vec2 p2 = stroke[s];

        // Use p1 -> p2 as the displacement vector
        glm::vec2 delta = p2 - p1;
        float dist_moved = glm::length(delta);
        if (dist_moved < 1e-6f) continue;

        // Normalize stroke point and delta to [0, 1] relative to bitmap dimensions
        glm::vec2 p1_norm = {p1.x / mWidth, p1.y / mHeight};
        glm::vec2 delta_norm = {delta.x / mWidth, delta.y / mHeight};

        for (auto& v : mVertices) {
            float d = glm::distance(v, p1_norm);
            if (d < normBrushSize) {
                // Standard Liquify: push pixels along the brush movement vector
                // Weight based on distance from brush center (Gaussian-like falloff)
                float weight = 1.0f - (d / normBrushSize);
                weight = weight * weight * (3.0f - 2.0f * weight); // Smoothstep

                v += delta_norm * weight * intensity;
            }
        }
    }

    mMeshDirty = true;
}


void ImageWarper::draw(int viewportWidth, int viewportHeight) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mInitialized || !mTextureId) return;

    if (mMeshDirty) {
        glBindBuffer(GL_ARRAY_BUFFER, mVboPos);
        glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)(mVertices.size() * sizeof(glm::vec2)), mVertices.data(), GL_DYNAMIC_DRAW);
        mMeshDirty = false;
    }

    glViewport(0, 0, viewportWidth, viewportHeight);
    glUseProgram(mProgram);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, mTextureId);
    glUniform1i(glGetUniformLocation(mProgram, "uTexture"), 0);

    glEnableVertexAttribArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, mVboPos);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);

    glEnableVertexAttribArray(1);
    glBindBuffer(GL_ARRAY_BUFFER, mVboTex);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, nullptr);

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIbo);
    glDrawElements(GL_TRIANGLES, (GLsizei)mIndices.size(), GL_UNSIGNED_INT, nullptr);

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
}

void ImageWarper::bakeToBitmap(uint8_t* outData) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mFbo || !mOffscreenTexture) return;

    glBindFramebuffer(GL_FRAMEBUFFER, mFbo);
    draw(mWidth, mHeight);

    glReadPixels(0, 0, mWidth, mHeight, GL_RGBA, GL_UNSIGNED_BYTE, outData);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}
