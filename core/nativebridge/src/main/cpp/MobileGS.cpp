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
#include <sys/resource.h>
#include <glm/glm.hpp>
#include <glm/gtc/quaternion.hpp>
#include <glm/gtc/matrix_transform.hpp>
#include <glm/gtc/type_ptr.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GraffitiJNI", __VA_ARGS__)

// Stores the last splat pipeline trace for in-app diagnostics (extern'd in GraffitiJNI.cpp).
std::string gLastSplatTrace;
#define SPLAT_TRACE(fmt, ...) do {     char _buf[256];     snprintf(_buf, sizeof(_buf), fmt, ##__VA_ARGS__);     LOGI("SPLAT_PIPE: %s", _buf);     gLastSplatTrace += std::string(_buf) + "\n"; } while(0)
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

    // FIX (Bug 5): Reset mesh state so stale wireframe geometry does not keep
    // rendering after a map clear.
    {
        std::lock_guard<std::mutex> glLock(mGlDataMutex);
        mPendingMeshVertices.clear();
        mPendingMeshIndices.clear();
        mGlDataDirty = true;
    }
    mMeshIndexCount = 0;

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
    // Column-major view matrix: camera world pos = -Rᵀ * t
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
    setpriority(PRIO_PROCESS, 0, 15);
    JniThreadAttacher attacher;

    while (mSortRunning) {
        std::vector<cv::Point3f> positions;
        cv::Point3f camPos;
        int currentCount = 0;

        {
            std::unique_lock<std::mutex> lock(mSortMutex);
            mSortCv.wait(lock, [this] { return mSortRequested || !mSortRunning; });
            if (!mSortRunning) break;

            {
                std::lock_guard<std::mutex> mainLock(mMutex);
                camPos = getCameraWorldPosition();
            }

            {
                std::lock_guard<std::mutex> mapLock(mMapMutex);
                currentCount = mPointCount;
                positions.resize(currentCount);
                for (int i = 0; i < currentCount; ++i) {
                    positions[i] = cv::Point3f(splatData[i].x, splatData[i].y, splatData[i].z);
                }
            }
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
}

// FIX (Bug 1): The original code computed R from the column-major view matrix and
// then did R * (p_cam - t) where t = V[12..14]. This is wrong.
//
// A column-major OpenGL view matrix encodes:  p_cam = R * p_world + t
// So the inverse is:  p_world = Rᵀ * p_cam - Rᵀ * t  =  Rᵀ * p_cam + camWorldPos
//
// The old code subtracted the view-space translation *before* rotating, which
// double-counted the translation and placed every world-space point at the wrong
// position (especially visible when the camera moves far from the origin).
static void camToWorld(const float* V, float xc, float yc, float zc,
                       float& xw, float& yw, float& zw) {
    // Rᵀ from column-major V:
    //   R col0 = (V[0], V[1], V[2])
    //   R col1 = (V[4], V[5], V[6])
    //   R col2 = (V[8], V[9], V[10])
    // Rᵀ row0 = (V[0], V[1], V[2]), etc.
    float tx = V[12], ty = V[13], tz = V[14];
    // Camera world position = -Rᵀ * t
    float camX = -(V[0]*tx + V[1]*ty + V[2]*tz);
    float camY = -(V[4]*tx + V[5]*ty + V[6]*tz);
    float camZ = -(V[8]*tx + V[9]*ty + V[10]*tz);
    // p_world = Rᵀ * p_cam + camWorldPos
    xw = V[0]*xc + V[1]*yc + V[2]*zc + camX;
    yw = V[4]*xc + V[5]*yc + V[6]*zc + camY;
    zw = V[8]*xc + V[9]*yc + V[10]*zc + camZ;
}

static void camToWorldNormal(const float* V, float nxc, float nyc, float nzc,
                             float& nxw, float& nyw, float& nzw) {
    // Normals transform by Rᵀ only (no translation component)
    nxw = V[0]*nxc + V[1]*nyc + V[2]*nzc;
    nyw = V[4]*nxc + V[5]*nyc + V[6]*nzc;
    nzw = V[8]*nxc + V[9]*nyc + V[10]*nzc;
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
    setpriority(PRIO_PROCESS, 0, 10);
    JniThreadAttacher attacher;

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

    const float* V = viewMat;
    // Recover pixel-space intrinsics from the OpenGL projection matrix.
    // Same derivation as processDepthFrame — must stay in sync.
    const float halfW_fp = depth.cols / 2.0f;
    const float halfH_fp = depth.rows / 2.0f;
    const float fx_fp = projMat[0] * halfW_fp;
    const float fy_fp = projMat[5] * halfH_fp;
    const float cx_fp = ( projMat[8]  + 1.0f) * halfW_fp;
    const float cy_fp = (-projMat[9]  + 1.0f) * halfH_fp;

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

        // Standard pinhole unproject — identical convention to processDepthFrame.
        float xc = (u - cx_fp) * d / fx_fp;
        float yc = -(v - cy_fp) * d / fy_fp;
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

void MobileGS::pushFrame(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, bool isYuv) {
    if (!mMapRunning) return;
    {
        std::lock_guard<std::mutex> lock(mQueueMutex);
        // Throttle: If queue is already full, replace oldest work to keep it fresh
        if (mFrameQueue.size() >= 2) {
            mFrameQueue.erase(mFrameQueue.begin());
        }
        FrameData data;
        data.depth = depth.clone();
        data.color = color.clone();
        data.isYuv = isYuv;
        memcpy(data.viewMatrix, viewMat, 16 * sizeof(float));
        memcpy(data.projMatrix, projMat, 16 * sizeof(float));
        mFrameQueue.push_back(std::move(data));
    }
    mQueueCv.notify_one();
}

void MobileGS::mapThreadFunc() {
    setpriority(PRIO_PROCESS, 0, 10);
    JniThreadAttacher attacher;
    while (mMapRunning) {
        FrameData frame;
        {
            std::unique_lock<std::mutex> lock(mQueueMutex);
            mQueueCv.wait(lock, [this] { return !mFrameQueue.empty() || !mMapRunning; });
            if (!mMapRunning) break;
            frame = std::move(mFrameQueue.front());
            mFrameQueue.erase(mFrameQueue.begin());
        }
        processDepthFrame(frame.depth, frame.color, frame.viewMatrix, frame.projMatrix, frame.isYuv);
    }
}

void MobileGS::processDepthFrame(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, bool isYuv) {
    bool isTrackingState = false;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        gLastSplatTrace.clear();
        if (depth.empty()) { SPLAT_TRACE("DROPPED - depth empty"); return; }
        if (color.empty()) { SPLAT_TRACE("DROPPED - color empty"); return; }
        SPLAT_TRACE("input depth=%dx%d color=%dx%d isYuv=%d", depth.cols, depth.rows, color.cols, color.rows, (int)isYuv);
        if (!mCameraReady) { SPLAT_TRACE("DROPPED - camera not ready"); return; }
        isTrackingState = mIsArCoreTracking;
    }
    if (!isTrackingState) { SPLAT_TRACE("DROPPED - not tracking"); return; }

    cv::Mat colorRGB;
    if (isYuv) {
        cv::cvtColor(color, colorRGB, cv::COLOR_YUV2RGB_NV21);
    } else {
        colorRGB = color;
    }

    const float* V = viewMat;

    // ARCore getProjectionMatrix returns a column-major OpenGL projection matrix where:
    //   projMat[0]  = 2*fx_px / imageWidth    (NOT fx in pixels directly)
    //   projMat[5]  = 2*fy_px / imageHeight
    //   projMat[8]  = (2*cx_px / imageWidth)  - 1   (NDC principal point offset)
    //   projMat[9]  = (2*cy_px / imageHeight) - 1   (negated for Y-down convention)
    //
    // We must recover pixel-space intrinsics from these NDC values using the
    // depth image dimensions (the space in which we are unprojecting).
    const float halfW = depth.cols / 2.0f;
    const float halfH = depth.rows / 2.0f;

    // fx_px = projMat[0] * halfW,  cx_px = (projMat[8] + 1) * halfW
    // fy_px = projMat[5] * halfH,  cy_px = (projMat[9] + 1) * halfH
    // (cy_px sign: ARCore [9] encodes -(2cy/h - 1) so we negate back)
    const float fx_px = projMat[0] * halfW;
    const float fy_px = projMat[5] * halfH;
    const float cx_px = (projMat[8]  + 1.0f) * halfW;
    const float cy_px = (-projMat[9] + 1.0f) * halfH;

    const float scaleX = (float)colorRGB.cols / depth.cols;
    const float scaleY = (float)colorRGB.rows / depth.rows;

    bool mapModified = false;
    // Optimized step: 8 for high-res, 4 for 640x480
    int step = (depth.cols > 640) ? 8 : 4;

    // Standard pinhole unproject: p_cam = [(c - cx) / fx, -(r - cy) / fy, -1] * d
    // Y is negated because image rows increase downward but camera Y points up.
    auto unproject = [&](int r, int c, float d) {
        return cv::Point3f(
            (c - cx_px) * d / fx_px,
           -(r - cy_px) * d / fy_px,
           -d
        );
    };

    // --- 1. Generate Splats from Depth Map ---
    std::vector<std::pair<VoxelKey, Splat>> newVoxelUpdates;
    newVoxelUpdates.reserve((depth.rows / step) * (depth.cols / step));

    // Log a sample depth value on every 30th frame to verify values are in range
    if (mFrameCounter % 30 == 0) {
        int midR = depth.rows / 2, midC = depth.cols / 2;
        float sampleD = depth.at<float>(midR, midC);
        SPLAT_TRACE("sample depth[%d,%d]=%.3fm fx_px=%.1f fy_px=%.1f cx_px=%.1f cy_px=%.1f",
             midR, midC, sampleD, fx_px, fy_px, cx_px, cy_px);
    }

    for (int r = 0; r < depth.rows - step; r += step) {
        const float* depthRow = depth.ptr<float>(r);
        const float* depthRowD = depth.ptr<float>(r + step);

        for (int c = 0; c < depth.cols - step; c += step) {
            float d = depthRow[c];
            float d_r = depthRow[c + step];
            float d_d = depthRowD[c];

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

                int colorR = static_cast<int>(r * scaleY);
                int colorC = static_cast<int>(c * scaleX);
                if (colorR < 0 || colorR >= colorRGB.rows || colorC < 0 || colorC >= colorRGB.cols) continue;

                cv::Vec3b col = colorRGB.at<cv::Vec3b>(colorR, colorC);
                float r_f = col[0]/255.0f, g_f = col[1]/255.0f, b_f = col[2]/255.0f;

                newVoxelUpdates.push_back({key, {xw, yw, zw, r_f, g_f, b_f, 1.0f, 0.1f, nx_w, ny_w, nz_w, 1.5f}});
                mapModified = true;
            }
        }
    }

    SPLAT_TRACE("frame processed depth=%dx%d step=%d candidates=%zu projMat[0]=%.4f projMat[5]=%.4f",
         depth.cols, depth.rows, step, newVoxelUpdates.size(),
         projMat[0], projMat[5]);

    if (mapModified) {
        std::lock_guard<std::mutex> mapLock(mMapMutex);
        for (const auto& update : newVoxelUpdates) {
            auto it = mVoxelGrid.find(update.first);
            if (it != mVoxelGrid.end()) {
                Splat& s = splatData[it->second];
                const Splat& nu = update.second;

                float colorDist = std::abs(s.r - nu.r) + std::abs(s.g - nu.g) + std::abs(s.b - nu.b);
                float alpha = s.confidence / (s.confidence + 1.0f);

                if (colorDist > 0.8f) {
                    s.confidence *= 0.5f;
                    alpha = 0.2f;
                }

                s.x = s.x * alpha + nu.x * (1.0f - alpha);
                s.y = s.y * alpha + nu.y * (1.0f - alpha);
                s.z = s.z * alpha + nu.z * (1.0f - alpha);
                s.r = s.r * alpha + nu.r * (1.0f - alpha);
                s.g = s.g * alpha + nu.g * (1.0f - alpha);
                s.b = s.b * alpha + nu.b * (1.0f - alpha);

                s.nx = s.nx * alpha + nu.nx * (1.0f - alpha);
                s.ny = s.ny * alpha + nu.ny * (1.0f - alpha);
                s.nz = s.nz * alpha + nu.nz * (1.0f - alpha);
                float len = std::sqrt(s.nx*s.nx + s.ny*s.ny + s.nz*s.nz);
                if(len > 0) { s.nx/=len; s.ny/=len; s.nz/=len; }

                s.confidence = std::min(1.0f, s.confidence + 0.05f);
            } else {
                splatData.push_back(update.second);
                mVoxelGrid[update.first] = splatData.size() - 1;
            }
        }

        if (splatData.size() >= MAX_SPLATS) {
            pruneMap();
        }
        mPointCount = static_cast<int>(splatData.size());
        SPLAT_TRACE("voxel insert done totalSplats=%d", mPointCount.load());
    }

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
        if (mapModified) {
            std::lock_guard<std::mutex> mapLock(mMapMutex);
            mPendingSplatData = splatData;
        }
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

    if (isTrackingState && (mFrameCounter - mLastFingerprintUpdateFrame) >= FINGERPRINT_UPDATE_INTERVAL) {
        {
            std::lock_guard<std::mutex> lk(mRelocMutex);
            mFingerprintColorFrame = colorRGB.clone();
            mFingerprintDepthFrame = depth.clone();
            memcpy(mFingerprintViewMatrix, viewMat, 16 * sizeof(float));
            memcpy(mFingerprintProjMatrix, projMat, 16 * sizeof(float));
            mFingerprintRequested = true;
        }
        mRelocCv.notify_one();
        mLastFingerprintUpdateFrame = mFrameCounter;
    }
}

void MobileGS::continuousOptimize() {
    std::lock_guard<std::mutex> mapLock(mMapMutex);
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
    // Assumes mMapMutex is already held by caller (processDepthFrame)
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
    {
        std::lock_guard<std::mutex> mapLock(mMapMutex);
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

    if (mPointVbo != 0 && mPointCount > 0) {
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glBufferData(GL_ARRAY_BUFFER, splatData.size() * sizeof(Splat), splatData.data(), GL_DYNAMIC_DRAW);
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

            if (mMeshVbo != 0) {
                if (!mPendingMeshVertices.empty()) {
                    glBindBuffer(GL_ARRAY_BUFFER, mMeshVbo);
                    glBufferData(GL_ARRAY_BUFFER, mPendingMeshVertices.size() * sizeof(float), mPendingMeshVertices.data(), GL_DYNAMIC_DRAW);
                    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mMeshIbo);
                    glBufferData(GL_ELEMENT_ARRAY_BUFFER, mPendingMeshIndices.size() * sizeof(uint32_t), mPendingMeshIndices.data(), GL_DYNAMIC_DRAW);
                    glBindBuffer(GL_ARRAY_BUFFER, 0);
                    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
                    mMeshIndexCount = mPendingMeshIndices.size();
                } else {
                    // Empty pending data (e.g. after clearMap) — zero the draw count.
                    mMeshIndexCount = 0;
                }
            }
            mGlDataDirty = false;
        }
    }

    std::lock_guard<std::mutex> lock(mMutex);
    interpolateAnchorStep();

    // FIX (Bug 4): Removed mPointCount == 0 early-exit so the wireframe renders
    // independently before any splats have accumulated.
    if (!mProgram || !mCameraReady) return;

    {
        std::lock_guard<std::mutex> sortLock(mSortMutex);
        if (mIndicesDirty) {
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIndexVbo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, mDrawIndices.size() * sizeof(uint32_t), mDrawIndices.data(), GL_DYNAMIC_DRAW);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
            mIndicesDirty = false;
        }
    }

    glEnable(GL_DEPTH_TEST);

    glm::mat4 V = glm::make_mat4(mViewMatrix);
    glm::mat4 A = glm::make_mat4(mAnchorMatrix);
    glm::mat4 P = glm::make_mat4(mProjMatrix);

    // Splats are anchored to a tracked pose — include A.
    glm::mat4 mvp = P * V * A;

    // FIX (Bug 0): Wireframe vertices are in world space; applying A collapsed all
    // lines to the anchor origin. Use P*V only.
    glm::mat4 meshMvp = P * V;

    // --- 1. Draw Splats ---
    if (mSplatsVisible && mPointCount > 0) {
        glUseProgram(mProgram);

        GLint mvpLoc = glGetUniformLocation(mProgram, "uMvp");
        glUniformMatrix4fv(mvpLoc, 1, GL_FALSE, glm::value_ptr(mvp));

        cv::Point3f camPos = getCameraWorldPosition();
        GLint camLoc = glGetUniformLocation(mProgram, "uCameraPos");
        glUniform3f(camLoc, camPos.x, camPos.y, camPos.z);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(GL_FALSE);

        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);

        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)0);

        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(12));

        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(28));

        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(32));

        glEnableVertexAttribArray(4);
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

        glDepthMask(GL_TRUE);
        glDisable(GL_BLEND);
    }

    // --- 2. Draw Wireframe Mesh ---
    if (mMeshProgram != 0 && mMeshIndexCount > 0) {
        glUseProgram(mMeshProgram);

        GLint meshMvpLoc = glGetUniformLocation(mMeshProgram, "uMvp");
        glUniformMatrix4fv(meshMvpLoc, 1, GL_FALSE, glm::value_ptr(meshMvp));

        GLint colorLoc = glGetUniformLocation(mMeshProgram, "uColor");
        glUniform4f(colorLoc, 0.0f, 1.0f, 1.0f, 0.2f); // Cyan, 20% alpha

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glBindBuffer(GL_ARRAY_BUFFER, mMeshVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, (void*)0);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mMeshIbo);

        glLineWidth(2.0f);
        glDrawElements(GL_LINES, mMeshIndexCount, GL_UNSIGNED_INT, (void*)0);

        glDisableVertexAttribArray(0);
        glDisable(GL_BLEND);
    }

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
}
