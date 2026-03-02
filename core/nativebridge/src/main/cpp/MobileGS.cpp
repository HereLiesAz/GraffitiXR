#include "MobileGS.h"
#include <algorithm>
#include <android/log.h>
#include <cstring>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GraffitiJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GraffitiJNI", __VA_ARGS__)

// Tuning constants
static constexpr size_t MAX_SPLATS = 500000;
static constexpr float VOXEL_SIZE = 0.02f;           // 20mm
static constexpr float CONFIDENCE_THRESHOLD = 0.6f;

static const char* kVertexShader =
    "#version 300 es\n"
    "layout(location = 0) in vec3 aPosition;\n"
    "layout(location = 1) in vec4 aColor;\n"
    "uniform mat4 uMvp;\n"
    "out vec4 vColor;\n"
    "void main() {\n"
    "  gl_Position = uMvp * vec4(aPosition, 1.0);\n"
    "  gl_PointSize = 4.0;\n"
    "  vColor = aColor;\n"
    "}\n";

static const char* kFragmentShader =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec4 vColor;\n"
    "out vec4 oColor;\n"
    "void main() {\n"
    "  oColor = vColor;\n"
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
    mFeatureDetector = cv::ORB::create(500);
    mMatcher = cv::DescriptorMatcher::create("BruteForce-Hamming");
    initShaders();
}

void MobileGS::destroy() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram) {
        glDeleteProgram(mProgram);
        mProgram = 0;
    }
    if (mPointVbo) {
        glDeleteBuffers(1, &mPointVbo);
        mPointVbo = 0;
    }
    if (mMeshVbo) {
        glDeleteBuffers(1, &mMeshVbo);
        mMeshVbo = 0;
    }
}

void MobileGS::initShaders() {
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    mProgram = glCreateProgram();
    glAttachShader(mProgram, vertexShader);
    glAttachShader(mProgram, fragmentShader);
    glLinkProgram(mProgram);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    glGenBuffers(1, &mPointVbo);
    glGenBuffers(1, &mMeshVbo);
}

void MobileGS::setArCoreTrackingState(bool isTracking) {
    std::lock_guard<std::mutex> lock(mMutex);
    mIsArCoreTracking = isTracking;
}

bool MobileGS::isTracking() const {
    return mIsArCoreTracking;
}

void MobileGS::pruneMap() {
    if (splatData.size() < MAX_SPLATS) return;

    const size_t evictCount = MAX_SPLATS / 10;

    std::partial_sort(splatData.begin(),
                      splatData.begin() + evictCount,
                      splatData.end(),
                      [](const Splat& a, const Splat& b) {
                          return a.confidence < b.confidence;
                      });

    splatData.erase(splatData.begin(), splatData.begin() + evictCount);
}

void MobileGS::processDepthFrame(const cv::Mat& depth, const cv::Mat& color) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mIsArCoreTracking || depth.empty()) return;

    // 1. Update Point Cloud
    int step = 16; 
    for (int r = 0; r < depth.rows; r += step) {
        for (int c = 0; c < depth.cols; c += step) {
            float d = depth.at<float>(r, c);
            if (d > 0.1f && d < 5.0f) {
                float x = (c - depth.cols / 2.0f) / (depth.cols / 2.0f) * d;
                float y = -(r - depth.rows / 2.0f) / (depth.rows / 2.0f) * d;
                float z = -d;

                cv::Vec3b col = color.at<cv::Vec3b>(r, c);
                splatData.push_back({x, y, z, col[0]/255.0f, col[1]/255.0f, col[2]/255.0f, 1.0f, 1.0f});
            }
        }
    }
    
    if (splatData.size() > MAX_SPLATS) pruneMap();
    mPointCount = static_cast<int>(splatData.size());
    
    glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
    // Buffer orphaning to avoid stalls
    glBufferData(GL_ARRAY_BUFFER, MAX_SPLATS * sizeof(Splat), nullptr, GL_DYNAMIC_DRAW);
    glBufferSubData(GL_ARRAY_BUFFER, 0, splatData.size() * sizeof(Splat), splatData.data());

    // 2. Generate Mesh
    std::vector<float> meshVertices;
    int mStep = 32;
    for (int r = 0; r < depth.rows - mStep; r += mStep) {
        for (int c = 0; c < depth.cols - mStep; c += mStep) {
            float d1 = depth.at<float>(r, c);
            float d2 = depth.at<float>(r + mStep, c);
            float d3 = depth.at<float>(r, c + mStep);

            if (d1 > 0.1f && d2 > 0.1f && d3 > 0.1f) {
                auto addVertex = [&](int row, int col, float dist) {
                    meshVertices.push_back((col - depth.cols / 2.0f) / (depth.cols / 2.0f) * dist);
                    meshVertices.push_back(-(row - depth.rows / 2.0f) / (depth.rows / 2.0f) * dist);
                    meshVertices.push_back(-dist);
                    meshVertices.push_back(1.0f); meshVertices.push_back(1.0f); 
                    meshVertices.push_back(1.0f); meshVertices.push_back(0.5f);
                };

                addVertex(r, c, d1); addVertex(r + mStep, c, d2);
                addVertex(r + mStep, c, d2); addVertex(r, c + mStep, d3);
                addVertex(r, c + mStep, d3); addVertex(r, c, d1);
            }
        }
    }
    mMeshVertexCount = static_cast<int>(meshVertices.size() / 7);
    glBindBuffer(GL_ARRAY_BUFFER, mMeshVbo);
    // Buffer orphaning
    glBufferData(GL_ARRAY_BUFFER, (depth.rows * depth.cols / (mStep * mStep)) * 6 * 7 * sizeof(float), nullptr, GL_DYNAMIC_DRAW);
    glBufferSubData(GL_ARRAY_BUFFER, 0, meshVertices.size() * sizeof(float), meshVertices.data());
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void MobileGS::attemptRelocalization(const cv::Mat& colorFrame) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mIsArCoreTracking || colorFrame.empty()) return;

    cv::Mat gray;
    cv::cvtColor(colorFrame, gray, cv::COLOR_RGB2GRAY);

    if (performPnP(gray)) {
        LOGI("Teleological target re-acquired via OpenCV. Awaiting ARCore handoff.");
    }
}

bool MobileGS::performPnP(const cv::Mat& grayFrame) {
    if (mTargetDescriptors.empty()) return false;

    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    mFeatureDetector->detectAndCompute(grayFrame, cv::noArray(), keypoints, descriptors);

    if (descriptors.empty()) return false;

    std::vector<cv::DMatch> matches;
    mMatcher->match(descriptors, mTargetDescriptors, matches);

    return false;
}

void MobileGS::updateCamera(float* viewMat, float* projMat) {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(mViewMatrix, viewMat, 16 * sizeof(float));
    memcpy(mProjMatrix, projMat, 16 * sizeof(float));
}

void MobileGS::updateAnchorTransform(float* transformMat) {
}

void MobileGS::draw() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mProgram || !mIsArCoreTracking) return;

    glUseProgram(mProgram);

    GLint mvpLoc = glGetUniformLocation(mProgram, "uMvp");
    glUniformMatrix4fv(mvpLoc, 1, GL_FALSE, mProjMatrix); 

    if (mPointCount > 0) {
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(3 * sizeof(float)));
        glDrawArrays(GL_POINTS, 0, mPointCount);
    }

    if (mMeshVertexCount > 0) {
        glBindBuffer(GL_ARRAY_BUFFER, mMeshVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)(3 * sizeof(float)));
        glDrawArrays(GL_LINES, 0, mMeshVertexCount);
    }

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}
