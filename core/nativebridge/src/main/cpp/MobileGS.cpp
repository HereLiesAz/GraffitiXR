// ~~~ FILE: ./core/nativebridge/src/main/cpp/MobileGS.cpp ~~~
#include "include/MobileGS.h"
#include <jni.h>
#include <EGL/egl.h>
#include <algorithm>
#include <android/log.h>
#include <cstring>
#include <vector>
#include <fstream>
#include <cmath>
#include <numeric>
#include <glm/glm.hpp>
#include <glm/gtc/quaternion.hpp>
#include <glm/gtc/matrix_transform.hpp>
#include <glm/gtc/type_ptr.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GraffitiJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GraffitiJNI", __VA_ARGS__)

extern JavaVM* gJvm; // Defined in GraffitiJNI.cpp

static constexpr size_t MAX_SPLATS = 500000;
static constexpr float VOXEL_SIZE = 0.02f; // 20mm voxels

// Helper RAII struct to guarantee JVM attachment for background threads
struct JniThreadAttacher {
    JNIEnv* env = nullptr;
    bool didAttach = false;

    JniThreadAttacher() {
        if (gJvm) {
            jint res = gJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
            if (res == JNI_EDETACHED) {
                if (gJvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                    didAttach = true;
                }
            }
        }
    }

    ~JniThreadAttacher() {
        if (didAttach && gJvm) {
            gJvm->DetachCurrentThread();
        }
    }
};

// --- SPLAT SHADERS ---
static const char* kVertexShader =
        "#version 300 es\n"
        "layout(location = 0) in vec3 aPosition;\n"
        "layout(location = 1) in vec4 aColor;\n"
        "layout(location = 2) in float aConfidence;\n"
        "layout(location = 3) in vec3 aNormal;\n"
        "layout(location = 4) in float aRadius;\n"
        "uniform mat4 uMvp;\n"
        "uniform vec3 uCameraPos;\n"
        "out vec4 vColor;\n"
        "out float vNdotV;\n"
        "void main() {\n"
        "  vec4 clip = uMvp * vec4(aPosition, 1.0);\n"
        "  gl_Position = clip;\n"
        "  vec3 viewDir = normalize(uCameraPos - aPosition);\n"
        "  vNdotV = abs(dot(aNormal, viewDir));\n"
        "  float sz = (10.0 + 30.0 * aConfidence) * aRadius / clip.w;\n"
        "  gl_PointSize = clamp(sz, 4.0, 128.0);\n"
        "  vColor = aColor;\n"
        "}\n";

static const char* kFragmentShader =
        "#version 300 es\n"
        "precision mediump float;\n"
        "in vec4 vColor;\n"
        "in float vNdotV;\n"
        "out vec4 oColor;\n"
        "void main() {\n"
        "  vec2 d = gl_PointCoord - 0.5;\n"
        "  float r2 = dot(d, d) * 4.0;\n"
        "  if (r2 > 1.0) discard;\n"
        "  float alpha = exp(-4.0 * r2);\n"
        "  float finalAlpha = alpha * vColor.a * vNdotV * 0.9;\n"
        "  oColor = vec4(vColor.rgb, finalAlpha);\n"
        "}\n";

// --- MESH (WIREFRAME) SHADERS ---
static const char* kMeshVertexShader =
        "#version 300 es\n"
        "layout(location = 0) in vec3 aPosition;\n"
        "uniform mat4 uMvp;\n"
        "void main() {\n"
        "  gl_Position = uMvp * vec4(aPosition, 1.0);\n"
        "}\n";

static const char* kMeshFragmentShader =
        "#version 300 es\n"
        "precision mediump float;\n"
        "uniform vec4 uColor;\n"
        "out vec4 oColor;\n"
        "void main() {\n"
        "  oColor = uColor;\n"
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
    mScreenWidth = width;
    mScreenHeight = height;
    mFeatureDetector = cv::ORB::create(500);
    mMatcher = cv::DescriptorMatcher::create("BruteForce-Hamming");

    memset(mViewMatrix, 0, sizeof(mViewMatrix));
    memset(mProjMatrix, 0, sizeof(mProjMatrix));
    memset(mAnchorMatrix, 0, sizeof(mAnchorMatrix));
    memset(mTargetAnchorMatrix, 0, sizeof(mTargetAnchorMatrix));
    mViewMatrix[0] = mViewMatrix[5] = mViewMatrix[10] = mViewMatrix[15] = 1.0f;
    mProjMatrix[0] = mProjMatrix[5] = mProjMatrix[10] = mProjMatrix[15] = 1.0f;
    mAnchorMatrix[0] = mAnchorMatrix[5] = mAnchorMatrix[10] = mAnchorMatrix[15] = 1.0f;
    mTargetAnchorMatrix[0] = mTargetAnchorMatrix[5] = mTargetAnchorMatrix[10] = mTargetAnchorMatrix[15] = 1.0f;

    mFrameCounter = 0;
    splatData.reserve(MAX_SPLATS);
    mDrawIndices.reserve(MAX_SPLATS);

    if (!mSortRunning) {
        mSortRunning = true;
        mSortThread = std::thread(&MobileGS::sortThreadFunc, this);
    }

    if (!mRelocRunning) {
        mRelocRunning = true;
        mRelocThread = std::thread(&MobileGS::relocThreadFunc, this);
    }

    if (!mMapRunning) {
        mMapRunning = true;
        mMapThread = std::thread(&MobileGS::mapThreadFunc, this);
    }

    LOGI("MobileGS initialized (CPU side) with %dx%d", width, height);
}

void MobileGS::initGl() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram != 0) return;
    initShaders();
    LOGI("MobileGS GL initialized");
}

void MobileGS::destroy() {
    if (mMapRunning) {
        mMapRunning = false;
        mQueueCv.notify_all();
        if (mMapThread.joinable()) mMapThread.join();
    }

    if (mSortRunning) {
        mSortRunning = false;
        mSortCv.notify_all();
        if (mSortThread.joinable()) {
            mSortThread.join();
        }
    }

    if (mRelocRunning) {
        mRelocRunning = false;
        mRelocCv.notify_all();
        if (mRelocThread.joinable()) {
            mRelocThread.join();
        }
    }

    std::lock_guard<std::mutex> lock(mMutex);

    // Only attempt to manually delete OpenGL objects if we are on a thread that actually has an EGL context.
    // If the Activity is destroyed, the OS reclaims the GL memory automatically. Attempting to call glDeleteProgram
    // from the main thread will throw a fatal EGL_NO_CONTEXT error.
    if (eglGetCurrentContext() != EGL_NO_CONTEXT) {
        if (mProgram) { glDeleteProgram(mProgram); mProgram = 0; }
        if (mPointVbo) { glDeleteBuffers(1, &mPointVbo); mPointVbo = 0; }
        if (mIndexVbo) { glDeleteBuffers(1, &mIndexVbo); mIndexVbo = 0; }

        if (mMeshProgram) { glDeleteProgram(mMeshProgram); mMeshProgram = 0; }
        if (mMeshVbo) { glDeleteBuffers(1, &mMeshVbo); mMeshVbo = 0; }
        if (mMeshIbo) { glDeleteBuffers(1, &mMeshIbo); mMeshIbo = 0; }
    } else {
        // Discard handles since the context is already dead.
        mProgram = 0; mPointVbo = 0; mIndexVbo = 0;
        mMeshProgram = 0; mMeshVbo = 0; mMeshIbo = 0;
    }
}

void MobileGS::initShaders() {
    // 1. Splat Shaders
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vertexShader && fragmentShader) {
        mProgram = glCreateProgram();
        glAttachShader(mProgram, vertexShader);
        glAttachShader(mProgram, fragmentShader);
        glLinkProgram(mProgram);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        glGenBuffers(1, &mPointVbo);
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glBufferData(GL_ARRAY_BUFFER, MAX_SPLATS * sizeof(Splat), nullptr, GL_DYNAMIC_DRAW);

        glGenBuffers(1, &mIndexVbo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIndexVbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, MAX_SPLATS * sizeof(uint32_t), nullptr, GL_DYNAMIC_DRAW);
    }

    // 2. Mesh Shaders
    GLuint meshVs = compileShader(GL_VERTEX_SHADER, kMeshVertexShader);
    GLuint meshFs = compileShader(GL_FRAGMENT_SHADER, kMeshFragmentShader);
    if (meshVs && meshFs) {
        mMeshProgram = glCreateProgram();
        glAttachShader(mMeshProgram, meshVs);
        glAttachShader(mMeshProgram, meshFs);
        glLinkProgram(mMeshProgram);
        glDeleteShader(meshVs);
        glDeleteShader(meshFs);

        glGenBuffers(1, &mMeshVbo);
        glGenBuffers(1, &mMeshIbo);
    }

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
}

void MobileGS::setArCoreTrackingState(bool isTracking) {
    std::lock_guard<std::mutex> lock(mMutex);
    mIsArCoreTracking = isTracking;
}

void MobileGS::clearMap() {
    std::lock_guard<std::mutex> lock(mMutex);
    splatData.clear();
    mVoxelGrid.clear();
    mPointCount = 0;
    mTargetDescriptors = cv::Mat();
    mTargetKeypoints3D.clear();

    memset(mAnchorMatrix, 0, sizeof(mAnchorMatrix));
    memset(mTargetAnchorMatrix, 0, sizeof(mTargetAnchorMatrix));
    mAnchorMatrix[0] = mAnchorMatrix[5] = mAnchorMatrix[10] = mAnchorMatrix[15] = 1.0f;
    mTargetAnchorMatrix[0] = mTargetAnchorMatrix[5] = mTargetAnchorMatrix[10] = mTargetAnchorMatrix[15] = 1.0f;
    mAnchorInterpolating = false;
    mInterpolationProgress = 0.0f;

    mFrameCounter = 0;
    mLastRelocTriggerFrame = 0;
    mLastFingerprintUpdateFrame = 0;
    LOGI("MobileGS: map cleared for new project");
}

void MobileGS::setViewportSize(int width, int height) {
    std::lock_guard<std::mutex> lock(mMutex);
    mScreenWidth = width;
    mScreenHeight = height;
}

void MobileGS::setRelocEnabled(bool enabled) {
    mRelocEnabled = enabled;
}

cv::Point3f MobileGS::getCameraWorldPosition() const {
    float r00 = mViewMatrix[0], r10 = mViewMatrix[1], r20 = mViewMatrix[2];
    float r01 = mViewMatrix[4], r11 = mViewMatrix[5], r21 = mViewMatrix[6];
    float r02 = mViewMatrix[8], r12 = mViewMatrix[9], r22 = mViewMatrix[10];
    float tx = mViewMatrix[12], ty = mViewMatrix[13], tz = mViewMatrix[14];

    float cx = -(r00*tx + r10*ty + r20*tz);
    float cy = -(r01*tx + r11*ty + r21*tz);
    float cz = -(r02*tx + r12*ty + r22*tz);
    return cv::Point3f(cx, cy, cz);
}

void MobileGS::sortThreadFunc() {
    JNIEnv* env = nullptr;
    bool attached = false;
    if (gJvm && gJvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        gJvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }

    JniThreadAttacher attacher; // Explicitly ties JVM to thread scope

    while (mSortRunning) {
        std::vector<cv::Point3f> positions;
        cv::Point3f camPos;
        int currentCount = 0;

        {
            std::unique_lock<std::mutex> lock(mSortMutex);
            mSortCv.wait(lock, [this] { return mSortRequested || !mSortRunning; });
            if (!mSortRunning) break;

            std::lock_guard<std::mutex> mainLock(mMutex);
            currentCount = mPointCount;
            positions.resize(currentCount);
            for (int i = 0; i < currentCount; ++i) {
                positions[i] = cv::Point3f(splatData[i].x, splatData[i].y, splatData[i].z);
            }
            camPos = getCameraWorldPosition();
            mSortRequested = false;
        }

        if (currentCount == 0) continue;

        std::vector<uint32_t> indices(currentCount);
        std::iota(indices.begin(), indices.end(), 0);

        std::sort(indices.begin(), indices.end(), [&](uint32_t a, uint32_t b) {
            cv::Point3f da = positions[a] - camPos;
            cv::Point3f db = positions[b] - camPos;
            return (da.x*da.x + da.y*da.y + da.z*da.z) > (db.x*db.x + db.y*db.y + db.z*db.z);
        });

        {
            std::lock_guard<std::mutex> lock(mSortMutex);
            mDrawIndices = std::move(indices);
            mIndicesDirty = true;
        }
    }

    if (attached && gJvm) {
        gJvm->DetachCurrentThread();
    }
}

static void camToWorld(const float* V, float xc, float yc, float zc,
                       float& xw, float& yw, float& zw) {
    float R[9] = { V[0], V[4], V[8], V[1], V[5], V[9], V[2], V[6], V[10] };
    float t[3] = { V[12], V[13], V[14] };
    float dx = xc - t[0];
    float dy = yc - t[1];
    float dz = zc - t[2];
    xw = R[0]*dx + R[3]*dy + R[6]*dz;
    yw = R[1]*dx + R[4]*dy + R[7]*dz;
    zw = R[2]*dx + R[5]*dy + R[8]*dz;
}

static void camToWorldNormal(const float* V, float nxc, float nyc, float nzc,
                             float& nxw, float& nyw, float& nzw) {
    float R[9] = { V[0], V[4], V[8], V[1], V[5], V[9], V[2], V[6], V[10] };
    nxw = R[0]*nxc + R[3]*nyc + R[6]*nzc;
    nyw = R[1]*nxc + R[4]*nyc + R[7]*nzc;
    nzw = R[2]*nxc + R[5]*nyc + R[8]*nzc;
}

void MobileGS::interpolateAnchorStep() {
    if (!mAnchorInterpolating) return;
    mInterpolationProgress = std::min(1.0f, mInterpolationProgress + INTERP_STEP);
    float t = mInterpolationProgress;

    glm::mat4 cur = glm::make_mat4(mAnchorMatrix);
    glm::mat4 tgt = glm::make_mat4(mTargetAnchorMatrix);

    glm::quat qCur = glm::quat_cast(glm::mat3(cur));
    glm::quat qTgt = glm::quat_cast(glm::mat3(tgt));
    glm::vec3 tCur = glm::vec3(cur[3]);
    glm::vec3 tTgt = glm::vec3(tgt[3]);

    glm::mat4 result = glm::mat4_cast(glm::slerp(qCur, qTgt, t));
    result[3] = glm::vec4(glm::mix(tCur, tTgt, t), 1.0f);
    memcpy(mAnchorMatrix, glm::value_ptr(result), 16 * sizeof(float));

    if (t >= 1.0f) mAnchorInterpolating = false;
}

void MobileGS::scheduleRelocCheck(const cv::Mat& colorFrame) {
    if (!mRelocEnabled) return;
    {
        std::lock_guard<std::mutex> lk(mRelocMutex);
        colorFrame.copyTo(mRelocColorFrame);
    }
    bool triggerNow = !mIsArCoreTracking ||
                      (mFrameCounter - mLastRelocTriggerFrame >= LOOP_CLOSURE_INTERVAL);
    if (triggerNow) {
        mLastRelocTriggerFrame = mFrameCounter;
        mRelocRequested = true;
        mRelocCv.notify_one();
    }
}

void MobileGS::relocThreadFunc() {
    JniThreadAttacher attacher; // Explicitly ties JVM to thread scope

    JNIEnv* env = nullptr;
    bool attached = false;
    if (gJvm && gJvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        gJvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }

    while (mRelocRunning) {
        cv::Mat relocFrame;
        cv::Mat fpColor;
        cv::Mat fpDepth;
        float fpView[16];
        float fpProj[16];
        bool doReloc = false;
        bool doFp = false;

        {
            std::unique_lock<std::mutex> lock(mRelocMutex);
            mRelocCv.wait(lock,[this] { return mRelocRequested || mFingerprintRequested || !mRelocRunning; });
            if (!mRelocRunning) break;

            if (mRelocRequested) {
                relocFrame = mRelocColorFrame.clone();
                mRelocRequested = false;
                doReloc = true;
            }
            if (mFingerprintRequested) {
                fpColor = mFingerprintColorFrame.clone();
                fpDepth = mFingerprintDepthFrame.clone();
                memcpy(fpView, mFingerprintViewMatrix, 16 * sizeof(float));
                memcpy(fpProj, mFingerprintProjMatrix, 16 * sizeof(float));
                mFingerprintRequested = false;
                doFp = true;
            }
        }

        if (doReloc && !relocFrame.empty()) {
            runPnPMatch(relocFrame);
        }
        if (doFp && !fpColor.empty() && !fpDepth.empty()) {
            tryUpdateFingerprint(fpColor, fpDepth, fpView, fpProj);
        }
    }

    if (attached && gJvm) {
        gJvm->DetachCurrentThread();
    }
}

void MobileGS::runPnPMatch(const cv::Mat& frame) {
    cv::Mat targetDesc;
    std::vector<cv::Point3f> targetPts;
    float projMat[16], anchorMat[16];
    int screenW, screenH;
    bool isTracking;
    {
        std::lock_guard<std::mutex> lk(mMutex);
        if (mTargetDescriptors.empty() || mTargetKeypoints3D.empty()) return;
        targetDesc = mTargetDescriptors.clone();
        targetPts  = mTargetKeypoints3D;
        memcpy(projMat,   mProjMatrix,   16 * sizeof(float));
        memcpy(anchorMat, mAnchorMatrix, 16 * sizeof(float));
        screenW    = mScreenWidth;
        screenH    = mScreenHeight;
        isTracking = mIsArCoreTracking;
    }

    cv::Mat gray;
    cv::cvtColor(frame, gray, cv::COLOR_RGB2GRAY);

    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;
    bool usedSP = mSuperPoint.isLoaded() && (targetDesc.type() != CV_8U)
                  && mSuperPoint.detect(gray, kps, descs);
    if (!usedSP) {
        cv::ORB::create(500)->detectAndCompute(gray, cv::noArray(), kps, descs);
    }
    if (descs.empty()) return;
    if (descs.type() != targetDesc.type()) return;

    auto matcher = cv::BFMatcher::create(
            descs.type() == CV_8U ? cv::NORM_HAMMING : cv::NORM_L2);

    std::vector<std::vector<cv::DMatch>> knnMatches;
    matcher->knnMatch(targetDesc, descs, knnMatches, 2);

    std::vector<cv::Point3f> objPts;
    std::vector<cv::Point2f> imgPts;
    for (const auto& m : knnMatches) {
        if (m.size() == 2 && m[0].distance < 0.75f * m[1].distance
            && m[0].queryIdx < (int)targetPts.size()) {
            objPts.push_back(targetPts[m[0].queryIdx]);
            imgPts.push_back(kps[m[0].trainIdx].pt);
        }
    }

    if (objPts.size() < 10) return;

    cv::Mat rvec, tvec;
    float fx   = projMat[0] * screenW / 2.0f;
    float fy   = projMat[5] * screenH / 2.0f;
    float cx_p = screenW / 2.0f;
    float cy_p = screenH / 2.0f;

    cv::Mat cameraMatrix = (cv::Mat_<double>(3, 3) << fx, 0, cx_p, 0, fy, cy_p, 0, 0, 1);
    cv::Mat distCoeffs   = cv::Mat::zeros(4, 1, CV_64F);

    bool success = cv::solvePnPRansac(objPts, imgPts, cameraMatrix, distCoeffs, rvec, tvec);
    if (!success) return;

    cv::Mat R;
    cv::Rodrigues(rvec, R);
    cv::Mat T = cv::Mat::eye(4, 4, CV_32F);
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) T.at<float>(i, j) = R.at<double>(i, j);
        T.at<float>(i, 3) = tvec.at<double>(i, 0);
    }

    float drift = glm::length(
            glm::vec3(T.at<float>(0, 3), T.at<float>(1, 3), T.at<float>(2, 3)) -
            glm::vec3(anchorMat[12], anchorMat[13], anchorMat[14])
    );

    if (drift > DRIFT_THRESHOLD_M || !isTracking) {
        std::lock_guard<std::mutex> lk(mMutex);
        memcpy(mTargetAnchorMatrix, T.data, 16 * sizeof(float));
        mInterpolationProgress = 0.0f;
        mAnchorInterpolating   = true;
    }
}

void MobileGS::tryUpdateFingerprint(const cv::Mat& color, const cv::Mat& depth, const float* viewMat, const float* projMat) {
    if (depth.empty() || color.empty()) return;

    cv::Mat gray;
    cv::cvtColor(color, gray, cv::COLOR_RGB2GRAY);

    std::vector<cv::KeyPoint> allKps;
    cv::Mat allDescs;
    bool usedSP = mSuperPoint.isLoaded() && mSuperPoint.detect(gray, allKps, allDescs);
    if (!usedSP) {
        mFeatureDetector->detectAndCompute(gray, cv::noArray(), allKps, allDescs);
    }
    if (allDescs.empty()) return;

    int cellW = gray.cols / 3;
    int cellH = gray.rows / 3;
    auto isPeripheral = [&](const cv::KeyPoint& kp) {
        int col = std::min((int)(kp.pt.x / cellW), 2);
        int row = std::min((int)(kp.pt.y / cellH), 2);
        return !(row == 1 && col == 1);
    };

    const float* V  = viewMat;
    float fx  = projMat[0];
    float fy  = projMat[5];
    float px  = projMat[8];
    float py  = projMat[9];
    float halfW = depth.cols / 2.0f;
    float halfH = depth.rows / 2.0f;

    std::vector<cv::Point3f> newPts;
    cv::Mat newDescs;

    for (int i = 0; i < (int)allKps.size(); ++i) {
        const cv::KeyPoint& kp = allKps[i];
        if (!isPeripheral(kp)) continue;

        int u = (int)kp.pt.x;
        int v = (int)kp.pt.y;
        if (u < 0 || u >= depth.cols || v < 0 || v >= depth.rows) continue;

        float d = depth.at<float>(v, u);
        if (d <= 0.0f || d > 5.0f) continue;

        float x_ndc = (u - halfW) / halfW;
        float y_ndc = -(v - halfH) / halfH;
        float xc = (x_ndc + px) * d / fx;
        float yc = (y_ndc + py) * d / fy;
        float zc = -d;

        float xw, yw, zw;
        camToWorld(V, xc, yc, zc, xw, yw, zw);

        newPts.push_back(cv::Point3f(xw, yw, zw));
        newDescs.push_back(allDescs.row(i));
    }

    if (newPts.empty()) return;

    std::lock_guard<std::mutex> lock(mMutex);

    if (usedSP && !mTargetDescriptors.empty() && mTargetDescriptors.type() == CV_8U) {
        mTargetDescriptors = cv::Mat();
        mTargetKeypoints3D.clear();
    }

    mTargetKeypoints3D.insert(mTargetKeypoints3D.end(), newPts.begin(), newPts.end());

    if (mTargetDescriptors.empty()) {
        mTargetDescriptors = newDescs.clone();
    } else {
        if (mTargetDescriptors.type() == newDescs.type()) {
            cv::vconcat(mTargetDescriptors, newDescs, mTargetDescriptors);
        } else {
            mTargetDescriptors = newDescs.clone();
            mTargetKeypoints3D = newPts;
        }
    }

    if (mTargetKeypoints3D.size() > MAX_FINGERPRINT_KEYPOINTS) {
        size_t excess = mTargetKeypoints3D.size() - MAX_FINGERPRINT_KEYPOINTS;
        mTargetKeypoints3D.erase(mTargetKeypoints3D.begin(),
                                 mTargetKeypoints3D.begin() + excess);
        mTargetDescriptors = mTargetDescriptors
                .rowRange((int)excess, mTargetDescriptors.rows)
                .clone();
    }
}

void MobileGS::pushFrame(const cv::Mat& depth, const cv::Mat& color) {
    {
        std::lock_guard<std::mutex> lock(mQueueMutex);
        // Limit queue size to prevent OOM if processing is slow
        if (mFrameQueue.size() > 2) {
            mFrameQueue.erase(mFrameQueue.begin());
        }
        mFrameQueue.push_back({depth.clone(), color.clone()});
    }
    mQueueCv.notify_one();
}

void MobileGS::mapThreadFunc() {
    while (mMapRunning) {
        FrameData frame;
        {
            std::unique_lock<std::mutex> lock(mQueueMutex);
            mQueueCv.wait(lock, [this] { return !mFrameQueue.empty() || !mMapRunning; });
            if (!mMapRunning) break;
            frame = std::move(mFrameQueue.front());
            mFrameQueue.erase(mFrameQueue.begin());
        }
        processDepthFrame(frame.depth, frame.color);
    }
}

void MobileGS::processDepthFrame(const cv::Mat& depth, const cv::Mat& color) {
    std::unique_lock<std::mutex> lock(mMutex);
    if (!mIsArCoreTracking || depth.empty() || color.empty() || !mCameraReady) return;

    const float* V = mViewMatrix;
    float fx = mProjMatrix[0];
    float fy = mProjMatrix[5];
    float cx = mProjMatrix[8];
    float cy = mProjMatrix[9];

    const float halfW = depth.cols / 2.0f;
    const float halfH = depth.rows / 2.0f;

    bool mapModified = false;
    int step = 8;

    auto unproject = [&](int r, int c, float d) {
        float x_ndc = (c - halfW) / halfW;
        float y_ndc = -(r - halfH) / halfH;
        return cv::Point3f((x_ndc + cx) * d / fx, (y_ndc + cy) * d / fy, -d);
    };

    // --- 1. Generate Splats from Depth Map ---
    for (int r = 0; r < depth.rows - step; r += step) {
        for (int c = 0; c < depth.cols - step; c += step) {
            float d = depth.at<float>(r, c);
            float d_r = depth.at<float>(r, c + step);
            float d_d = depth.at<float>(r + step, c);

            if (d > 0.1f && d < 5.0f && d_r > 0.1f && d_d > 0.1f) {
                cv::Point3f p_cam = unproject(r, c, d);
                cv::Point3f p_r_cam = unproject(r, c + step, d_r);
                cv::Point3f p_d_cam = unproject(r + step, c, d_d);

                cv::Point3f v1 = p_r_cam - p_cam;
                cv::Point3f v2 = p_d_cam - p_cam;
                cv::Point3f n_cam = v1.cross(v2);

                if (n_cam.dot(p_cam) > 0) n_cam = -n_cam;

                float n_len = cv::norm(n_cam);
                if (n_len > 0.0001f) n_cam /= n_len;

                float xw, yw, zw;
                camToWorld(V, p_cam.x, p_cam.y, p_cam.z, xw, yw, zw);

                float nx_w, ny_w, nz_w;
                camToWorldNormal(V, n_cam.x, n_cam.y, n_cam.z, nx_w, ny_w, nz_w);

                VoxelKey key{
                        static_cast<int>(std::floor(xw / VOXEL_SIZE)),
                        static_cast<int>(std::floor(yw / VOXEL_SIZE)),
                        static_cast<int>(std::floor(zw / VOXEL_SIZE))
                };

                cv::Vec3b col = color.at<cv::Vec3b>(r, c);
                float r_f = col[0]/255.0f, g_f = col[1]/255.0f, b_f = col[2]/255.0f;

                auto it = mVoxelGrid.find(key);
                if (it != mVoxelGrid.end()) {
                    Splat& s = splatData[it->second];

                    float colorDist = std::abs(s.r - r_f) + std::abs(s.g - g_f) + std::abs(s.b - b_f);
                    float alpha = s.confidence / (s.confidence + 1.0f);

                    if (colorDist > 0.8f) {
                        s.confidence *= 0.5f;
                        alpha = 0.2f;
                    }

                    s.x = s.x * alpha + xw * (1.0f - alpha);
                    s.y = s.y * alpha + yw * (1.0f - alpha);
                    s.z = s.z * alpha + zw * (1.0f - alpha);
                    s.r = s.r * alpha + r_f * (1.0f - alpha);
                    s.g = s.g * alpha + g_f * (1.0f - alpha);
                    s.b = s.b * alpha + b_f * (1.0f - alpha);

                    s.nx = s.nx * alpha + nx_w * (1.0f - alpha);
                    s.ny = s.ny * alpha + ny_w * (1.0f - alpha);
                    s.nz = s.nz * alpha + nz_w * (1.0f - alpha);
                    float len = std::sqrt(s.nx*s.nx + s.ny*s.ny + s.nz*s.nz);
                    if(len > 0) { s.nx/=len; s.ny/=len; s.nz/=len; }

                    s.confidence = std::min(1.0f, s.confidence + 0.05f);
                } else {
                    splatData.push_back({xw, yw, zw, r_f, g_f, b_f, 1.0f, 0.1f, nx_w, ny_w, nz_w, 1.5f});
                    mVoxelGrid[key] = splatData.size() - 1;
                }
                mapModified = true;
            }
        }
    }

    if (splatData.size() >= MAX_SPLATS) {
        pruneMap();
    }

    mPointCount = static_cast<int>(splatData.size());

    // --- 2. Generate Live Surface Mesh (Wireframe) ---
    std::vector<float> meshVertices;
    std::vector<uint32_t> meshIndices;

    int gridW = depth.cols / step;
    int gridH = depth.rows / step;
    std::vector<int> vIndex(gridW * gridH, -1);

    for (int r = 0; r < depth.rows - step; r += step) {
        for (int c = 0; c < depth.cols - step; c += step) {
            float d = depth.at<float>(r, c);
            if (d > 0.1f && d < 5.0f) {
                cv::Point3f p_cam = unproject(r, c, d);
                float xw, yw, zw;
                camToWorld(V, p_cam.x, p_cam.y, p_cam.z, xw, yw, zw);

                int idx = meshVertices.size() / 3;
                meshVertices.push_back(xw);
                meshVertices.push_back(yw);
                meshVertices.push_back(zw);
                vIndex[(r / step) * gridW + (c / step)] = idx;
            }
        }
    }

    // Connect valid neighboring vertices to form lines
    for (int r = 0; r < gridH - 1; ++r) {
        for (int c = 0; c < gridW - 1; ++c) {
            int idx = vIndex[r * gridW + c];
            int right = vIndex[r * gridW + c + 1];
            int down = vIndex[(r + 1) * gridW + c];

            if (idx != -1) {
                if (right != -1) {
                    meshIndices.push_back(idx);
                    meshIndices.push_back(right);
                }
                if (down != -1) {
                    meshIndices.push_back(idx);
                    meshIndices.push_back(down);
                }
            }
        }
    }

    // --- 3. Double buffer the GL data ---
    {
        std::lock_guard<std::mutex> glLock(mGlDataMutex);
        mPendingSplatData = splatData;
        mPendingMeshVertices = std::move(meshVertices);
        mPendingMeshIndices = std::move(meshIndices);
        mGlDataDirty = true;
    }

    // --- 4. Background Optimization Triggers ---
    if (++mFrameCounter % 30 == 0) {
        continuousOptimize();
        {
            std::lock_guard<std::mutex> sortLock(mSortMutex);
            mSortRequested = true;
        }
        mSortCv.notify_one();
    }

    if (mIsArCoreTracking &&
        (mFrameCounter - mLastFingerprintUpdateFrame) >= FINGERPRINT_UPDATE_INTERVAL) {
        {
            std::lock_guard<std::mutex> lk(mRelocMutex);
            mFingerprintColorFrame = color.clone();
            mFingerprintDepthFrame = depth.clone();
            memcpy(mFingerprintViewMatrix, mViewMatrix, 16 * sizeof(float));
            memcpy(mFingerprintProjMatrix, mProjMatrix, 16 * sizeof(float));
            mFingerprintRequested = true;
        }
        mRelocCv.notify_one();
        mLastFingerprintUpdateFrame = mFrameCounter;
    }
}

void MobileGS::continuousOptimize() {
    if (splatData.empty()) return;

    bool needsRebuild = false;
    size_t validCount = 0;

    for (size_t i = 0; i < splatData.size(); i++) {
        splatData[i].confidence -= 0.005f;

        if (splatData[i].confidence > 0.0f) {
            if (validCount != i) {
                splatData[validCount] = splatData[i];
            }
            validCount++;
        } else {
            needsRebuild = true;
        }
    }

    if (needsRebuild) {
        splatData.resize(validCount);
        mVoxelGrid.clear();
        for (size_t i = 0; i < splatData.size(); ++i) {
            VoxelKey key{
                    static_cast<int>(std::floor(splatData[i].x / VOXEL_SIZE)),
                    static_cast<int>(std::floor(splatData[i].y / VOXEL_SIZE)),
                    static_cast<int>(std::floor(splatData[i].z / VOXEL_SIZE))
            };
            mVoxelGrid[key] = i;
        }
        mPointCount = static_cast<int>(validCount);
    }
}

void MobileGS::pruneMap() {
    if (splatData.size() < MAX_SPLATS) return;

    const size_t evictCount = MAX_SPLATS / 10;
    const size_t keepCount = splatData.size() - evictCount;

    std::nth_element(splatData.begin(),
                     splatData.begin() + keepCount,
                     splatData.end(),[](const Splat& a, const Splat& b) {
                return a.confidence > b.confidence;
            });

    splatData.resize(keepCount);

    mVoxelGrid.clear();
    for (size_t i = 0; i < splatData.size(); ++i) {
        const auto& s = splatData[i];
        VoxelKey key{
                static_cast<int>(std::floor(s.x / VOXEL_SIZE)),
                static_cast<int>(std::floor(s.y / VOXEL_SIZE)),
                static_cast<int>(std::floor(s.z / VOXEL_SIZE))
        };
        mVoxelGrid[key] = i;
    }
}

void MobileGS::updateCamera(float* viewMat, float* projMat) {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(mViewMatrix, viewMat, 16 * sizeof(float));
    memcpy(mProjMatrix, projMat, 16 * sizeof(float));
    mCameraReady = true;
}

void MobileGS::updateLightLevel(float level) {
    std::lock_guard<std::mutex> lock(mMutex);

    float normalizedLight = std::clamp(level, 0.0f, 1.0f);
    int orbThreshold = (normalizedLight < 0.2f) ? 10 : (normalizedLight > 0.8f ? 45 : 31);

    // Only re-create if the threshold has actually changed to save CPU
    static int lastThreshold = -1;
    if (orbThreshold != lastThreshold) {
        mFeatureDetector = cv::ORB::create(500, 1.2f, 8, orbThreshold);
        lastThreshold = orbThreshold;
        LOGI("Sensitivity adjusted: light=%.2f, new ORB threshold=%d", level, orbThreshold);
    }
}

void MobileGS::setTargetFingerprint(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d) {
    std::lock_guard<std::mutex> lock(mMutex);
    mTargetDescriptors = descriptors.clone();
    mTargetKeypoints3D = points3d;
}

bool MobileGS::loadSuperPoint(const std::vector<uchar>& onnxBytes) {
    return mSuperPoint.load(onnxBytes);
}

void MobileGS::updateAnchorTransform(float* transformMat) {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(mAnchorMatrix, transformMat, 16 * sizeof(float));
}

void MobileGS::saveModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) return;

    char magic[4] = {'G','X','R','M'};
    out.write(magic, 4);
    int version = 3; // Version 3 handles 48-byte anisotropic Splat structs
    out.write((char*)&version, 4);
    int numSplats = splatData.size();
    out.write((char*)&numSplats, 4);
    int keyframes = 0;
    out.write((char*)&keyframes, 4);

    out.write((char*)splatData.data(), numSplats * sizeof(Splat));
    out.write((char*)mAnchorMatrix, 16 * sizeof(float));
    out.close();
}

void MobileGS::loadModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return;

    char magic[4];
    in.read(magic, 4);
    if (strncmp(magic, "GXRM", 4) != 0) return;

    int version, numSplats, keyframes;
    in.read((char*)&version, 4);
    in.read((char*)&numSplats, 4);
    in.read((char*)&keyframes, 4);

    if (version == 3) {
        splatData.resize(numSplats);
        in.read((char*)splatData.data(), numSplats * sizeof(Splat));
    } else if (version == 2) {
        struct LegacySplat { float x,y,z, r,g,b,a, conf; };
        std::vector<LegacySplat> legacy(numSplats);
        in.read((char*)legacy.data(), numSplats * sizeof(LegacySplat));
        splatData.resize(numSplats);
        for(int i=0; i<numSplats; i++) {
            splatData[i] = {legacy[i].x, legacy[i].y, legacy[i].z,
                            legacy[i].r, legacy[i].g, legacy[i].b, legacy[i].a,
                            legacy[i].conf,
                            0.0f, 0.0f, 1.0f, 1.0f};
        }
    } else {
        return;
    }

    in.read((char*)mAnchorMatrix, 16 * sizeof(float));
    in.close();

    mPointCount = numSplats;
    mVoxelGrid.clear();
    for (size_t i = 0; i < splatData.size(); ++i) {
        const auto& s = splatData[i];
        VoxelKey key{
                static_cast<int>(std::floor(s.x / VOXEL_SIZE)),
                static_cast<int>(std::floor(s.y / VOXEL_SIZE)),
                static_cast<int>(std::floor(s.z / VOXEL_SIZE))
        };
        mVoxelGrid[key] = i;
    }

    if (mPointVbo != 0 && mPointCount > 0) {
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, splatData.size() * sizeof(Splat), splatData.data());
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }
}

void MobileGS::draw() {
    {
        std::lock_guard<std::mutex> glLock(mGlDataMutex);
        if (mGlDataDirty) {
            if (mPointVbo != 0 && !mPendingSplatData.empty()) {
                glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
                glBufferData(GL_ARRAY_BUFFER, mPendingSplatData.size() * sizeof(Splat), mPendingSplatData.data(), GL_DYNAMIC_DRAW);
                glBindBuffer(GL_ARRAY_BUFFER, 0);
            }

            if (mMeshVbo != 0 && !mPendingMeshVertices.empty()) {
                glBindBuffer(GL_ARRAY_BUFFER, mMeshVbo);
                glBufferData(GL_ARRAY_BUFFER, mPendingMeshVertices.size() * sizeof(float), mPendingMeshVertices.data(), GL_DYNAMIC_DRAW);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mMeshIbo);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, mPendingMeshIndices.size() * sizeof(uint32_t), mPendingMeshIndices.data(), GL_DYNAMIC_DRAW);
                glBindBuffer(GL_ARRAY_BUFFER, 0);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
                mMeshIndexCount = mPendingMeshIndices.size();
            }
            mGlDataDirty = false;
        }
    }

    std::lock_guard<std::mutex> lock(mMutex);
    interpolateAnchorStep();
    if (!mProgram || !mCameraReady || mPointCount == 0) return;

    {
        std::lock_guard<std::mutex> sortLock(mSortMutex);
        if (mIndicesDirty) {
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIndexVbo);
            glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, 0, mDrawIndices.size() * sizeof(uint32_t), mDrawIndices.data());
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
            mIndicesDirty = false;
        }
    }

    glEnable(GL_DEPTH_TEST);

    // Apply Teleological Anchor to View
    float va[16];
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += mViewMatrix[k*4 + row] * mAnchorMatrix[col*4 + k];
            va[col*4 + row] = s;
        }
    }

    // Apply Projection
    float mvp[16];
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += mProjMatrix[k*4 + row] * va[col*4 + k];
            mvp[col*4 + row] = s;
        }
    }

    // --- 1. Draw Splats ---
    glUseProgram(mProgram);

    GLint mvpLoc = glGetUniformLocation(mProgram, "uMvp");
    glUniformMatrix4fv(mvpLoc, 1, GL_FALSE, mvp);

    cv::Point3f camPos = getCameraWorldPosition();
    GLint camLoc = glGetUniformLocation(mProgram, "uCameraPos");
    glUniform3f(camLoc, camPos.x, camPos.y, camPos.z);

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE); // Soft transparency

    glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);

    glEnableVertexAttribArray(0); // aPosition
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)0);

    glEnableVertexAttribArray(1); // aColor
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(12));

    glEnableVertexAttribArray(2); // aConfidence
    glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(28));

    glEnableVertexAttribArray(3); // aNormal
    glVertexAttribPointer(3, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(32));

    glEnableVertexAttribArray(4); // aRadius
    glVertexAttribPointer(4, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(44));

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIndexVbo);

    int elementsToDraw = std::min(mPointCount.load(), static_cast<int>(mDrawIndices.size()));
    if (elementsToDraw > 0) {
        glDrawElements(GL_POINTS, elementsToDraw, GL_UNSIGNED_INT, (void*)0);
    } else {
        glDrawArrays(GL_POINTS, 0, mPointCount);
    }

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glDisableVertexAttribArray(2);
    glDisableVertexAttribArray(3);
    glDisableVertexAttribArray(4);

    // --- 2. Draw Wireframe Mesh ---
    if (mMeshProgram != 0 && mMeshIndexCount > 0) {
        glUseProgram(mMeshProgram);

        GLint meshMvpLoc = glGetUniformLocation(mMeshProgram, "uMvp");
        glUniformMatrix4fv(meshMvpLoc, 1, GL_FALSE, mvp);

        GLint colorLoc = glGetUniformLocation(mMeshProgram, "uColor");
        glUniform4f(colorLoc, 0.0f, 1.0f, 1.0f, 0.2f); // Cyan, 20% alpha

        glBindBuffer(GL_ARRAY_BUFFER, mMeshVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, (void*)0);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mMeshIbo);

        glLineWidth(2.0f);
        glDrawElements(GL_LINES, mMeshIndexCount, GL_UNSIGNED_INT, (void*)0);

        glDisableVertexAttribArray(0);
    }

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

    glDepthMask(GL_TRUE);
    glDisable(GL_BLEND);
}