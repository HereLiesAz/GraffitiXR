#include "MobileGS.h"
#include <algorithm>
#include <android/log.h>
#include <cstring>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GraffitiJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GraffitiJNI", __VA_ARGS__)

static constexpr size_t MAX_SPLATS = 500000;
static constexpr float VOXEL_SIZE = 0.02f;
static constexpr float CONFIDENCE_THRESHOLD = 0.6f;

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
    "  // Larger splats for diagnostics\n"
    "  float sz = (100.0 + 80.0 * aConfidence) / clip.w;\n"
    "  gl_PointSize = clamp(sz, 5.0, 256.0);\n"
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
    "  oColor = vec4(vColor.rgb, alpha * vColor.a);\n"
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
    memset(mViewMatrix, 0, sizeof(mViewMatrix));
    memset(mProjMatrix, 0, sizeof(mProjMatrix));
    mViewMatrix[0] = mViewMatrix[5] = mViewMatrix[10] = mViewMatrix[15] = 1.0f;
    mProjMatrix[0] = mProjMatrix[5] = mProjMatrix[10] = mProjMatrix[15] = 1.0f;
    initShaders();
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
    LOGI("MobileGS Shaders linked OK.");

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
    if (mIsArCoreTracking != isTracking) {
        LOGI("Tracking changed: %s", isTracking ? "TRACKING" : "LOST");
    }
    mIsArCoreTracking = isTracking;
}

bool MobileGS::isTracking() const { return mIsArCoreTracking; }

static void camToWorld(const float* V, float xc, float yc, float zc,
                       float& xw, float& yw, float& zw) {
    float dx = xc - V[12], dy = yc - V[13], dz = zc - V[14];
    xw = V[0]*dx + V[1]*dy + V[2]*dz;
    yw = V[4]*dx + V[5]*dy + V[6]*dz;
    zw = V[8]*dx + V[9]*dy + V[10]*dz;
}

void MobileGS::processDepthFrame(const cv::Mat& depth, const cv::Mat& color) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mIsArCoreTracking || depth.empty() || color.empty()) return;

    const float* V = mViewMatrix;
    const float halfW = depth.cols / 2.0f;
    const float halfH = depth.rows / 2.0f;

    int pointsAdded = 0;
    int step = 32; // Skip more pixels for performance while diagnosing
    for (int r = 0; r < depth.rows; r += step) {
        for (int c = 0; c < depth.cols; c += step) {
            float d = depth.at<float>(r, c);
            if (d > 0.1f && d < 4.0f) {
                float xc = (c - halfW) / halfW * d;
                float yc = -(r - halfH) / halfH * d;
                float zc = -d;
                float xw, yw, zw;
                camToWorld(V, xc, yc, zc, xw, yw, zw);

                cv::Vec3b col = color.at<cv::Vec3b>(r, c);
                splatData.push_back({xw, yw, zw, col[0]/255.0f, col[1]/255.0f, col[2]/255.0f, 1.0f, 1.0f});
                pointsAdded++;
            }
        }
    }

    if (splatData.size() > MAX_SPLATS) splatData.erase(splatData.begin(), splatData.begin() + 1000);
    mPointCount = static_cast<int>(splatData.size());

    if (pointsAdded > 0) {
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, splatData.size() * sizeof(Splat), splatData.data());
    }
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void MobileGS::updateCamera(float* viewMat, float* projMat) {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(mViewMatrix, viewMat, 16 * sizeof(float));
    memcpy(mProjMatrix, projMat, 16 * sizeof(float));
    mCameraReady = true;
}

void MobileGS::draw() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mProgram || !mCameraReady) return;

    static int sDrawCount = 0;
    if ((sDrawCount++ % 120) == 0) {
        LOGI("MobileGS::draw: pts=%d trk=%d", mPointCount, (int)mIsArCoreTracking);
    }

    glEnable(GL_DEPTH_TEST);
    glUseProgram(mProgram);

    float mvp[16];
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += mProjMatrix[k*4 + row] * mViewMatrix[col*4 + k];
            mvp[col*4 + row] = s;
        }
    }
    GLint mvpLoc = glGetUniformLocation(mProgram, "uMvp");
    glUniformMatrix4fv(mvpLoc, 1, GL_FALSE, mvp);

    if (mPointCount > 0) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
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
    }
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void MobileGS::attemptRelocalization(const cv::Mat& colorFrame) {}
void MobileGS::updateAnchorTransform(float* transformMat) {}
void MobileGS::pruneMap() {}
