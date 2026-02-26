#include "MobileGS.h"
#include <android/log.h>
#include <fstream>
#include <ctime>
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>
#include <GLES3/gl3.h>

#define TAG "MobileGS"
#if defined(NDEBUG)
#define LOGI(...)
#define LOGE(...)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#endif

static void multiplyMatricesInternal(const float* a, const float* b, float* result) {
    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            float sum = 0.0f;
            for (int k = 0; k < 4; ++k) {
                sum += a[row + k * 4] * b[k + col * 4];
            }
            result[row + col * 4] = sum;
        }
    }
}

static GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetShaderInfoLog(shader, infoLen, NULL, infoLog);
            LOGE("Error compiling shader:\n%s", infoLog);
            delete[] infoLog;
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static GLuint createProgram(const char* vertSrc, const char* fragSrc) {
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, vertSrc);
    if (!vertexShader) return 0;
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragSrc);
    if (!fragmentShader) return 0;

    GLuint program = glCreateProgram();
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glLinkProgram(program);
    GLint linked;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("Error linking program");
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

MobileGS::MobileGS() {
    vulkanRenderer = new VulkanBackend();
    std::fill(std::begin(viewMtx), std::end(viewMtx), 0.0f);
    std::fill(std::begin(projMtx), std::end(projMtx), 0.0f);
    std::fill(std::begin(alignmentMtx), std::end(alignmentMtx), 0.0f);
    viewMtx[0] = viewMtx[5] = viewMtx[10] = viewMtx[15] = 1.0f;
    projMtx[0] = projMtx[5] = projMtx[10] = projMtx[15] = 1.0f;
    alignmentMtx[0] = alignmentMtx[5] = alignmentMtx[10] = alignmentMtx[15] = 1.0f;
}

MobileGS::~MobileGS() {
    if (vulkanRenderer) {
        delete vulkanRenderer;
        vulkanRenderer = nullptr;
    }
}

void MobileGS::initialize() { isInitialized = true; }

void MobileGS::reset() {
    isInitialized = false;
    mVoxelGrid.clear();
}

void MobileGS::onSurfaceChanged(int width, int height) {
    viewportWidth = width;
    viewportHeight = height;
    glViewport(0, 0, width, height);
    if (vulkanRenderer && visMode == 0) vulkanRenderer->resize(width, height);
}

void MobileGS::draw() {
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    if (!isInitialized) return;

    if (visMode == 0 && vulkanRenderer) {
        vulkanRenderer->setLighting(lightIntensity, lightColor);
        vulkanRenderer->renderFrame();
    }
    else if (visMode == 1) {
        if (pointProgram == 0) {
            const char* vShaderStr =
                    "#version 300 es\n"
                    "layout(location = 0) in vec3 a_Position;\n"
                    "layout(location = 1) in vec4 a_Color;\n"
                    "uniform mat4 u_MvpMatrix;\n"
                    "out vec4 v_Color;\n"
                    "void main() {\n"
                    "  gl_Position = u_MvpMatrix * vec4(a_Position, 1.0);\n"
                    "  gl_PointSize = 15.0;\n"
                    "  v_Color = a_Color;\n"
                    "}\n";
            const char* fShaderStr =
                    "#version 300 es\n"
                    "precision mediump float;\n"
                    "in vec4 v_Color;\n"
                    "out vec4 o_FragColor;\n"
                    "void main() {\n"
                    "  vec2 coord = gl_PointCoord - vec2(0.5);\n"
                    "  if(length(coord) > 0.5) discard;\n"
                    "  o_FragColor = v_Color;\n"
                    "}\n";
            pointProgram = createProgram(vShaderStr, fShaderStr);
            if (pointProgram == 0) { LOGE("Failed to create point program"); return; }
        }

        glUseProgram(pointProgram);

        float finalView[16];
        multiplyMatricesInternal(viewMtx, alignmentMtx, finalView);

        float mvp[16];
        multiplyMatricesInternal(projMtx, finalView, mvp);

        GLint mvpLoc = glGetUniformLocation(pointProgram, "u_MvpMatrix");
        glUniformMatrix4fv(mvpLoc, 1, GL_FALSE, mvp);

        std::vector<float> pointData;
        {
            std::lock_guard<std::mutex> lock(pointMutex);
            if (!mVoxelGrid.empty()) {
                pointData.reserve(mVoxelGrid.size() * 7);
                for (const auto& pair : mVoxelGrid) {
                    const auto& p = pair.second;
                    if (p.confidence > 0.2f) {
                        pointData.push_back(p.x); pointData.push_back(p.y); pointData.push_back(p.z);
                        pointData.push_back(p.r); pointData.push_back(p.g); pointData.push_back(p.b);
                        pointData.push_back(p.a * p.confidence);
                    }
                }
            }
        }

        if (!pointData.empty()) {
            if (pointVBO == 0) glGenBuffers(1, &pointVBO);
            glBindBuffer(GL_ARRAY_BUFFER, pointVBO);
            glBufferData(GL_ARRAY_BUFFER, pointData.size() * sizeof(float), pointData.data(), GL_DYNAMIC_DRAW);

            glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)0);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)(3 * sizeof(float)));
            glEnableVertexAttribArray(1);

            glDrawArrays(GL_POINTS, 0, pointData.size() / 7);

            glDisableVertexAttribArray(0);
            glDisableVertexAttribArray(1);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
    }
}

bool MobileGS::initVulkan(ANativeWindow* window, AAssetManager* mgr) {
    return vulkanRenderer ? vulkanRenderer->initialize(window, mgr) : false;
}

void MobileGS::resizeVulkan(int width, int height) {
    if (vulkanRenderer) vulkanRenderer->resize(width, height);
}

void MobileGS::destroyVulkan() {
    if (vulkanRenderer) vulkanRenderer->destroy();
}

void MobileGS::updateCamera(float* view, float* proj) {
    if (view && proj) {
        std::copy(view, view + 16, viewMtx);
        std::copy(proj, proj + 16, projMtx);

        if (visMode == 0 && vulkanRenderer) {
            float finalView[16];
            {
                std::lock_guard<std::mutex> lock(alignMutex);
                multiplyMatricesInternal(view, alignmentMtx, finalView);
            }
            vulkanRenderer->updateCamera(finalView, projMtx);
        }
    }
}

void MobileGS::alignMap(float* transform) {
    if (transform) {
        std::lock_guard<std::mutex> lock(alignMutex);
        std::copy(transform, transform + 16, alignmentMtx);
    }
}

void MobileGS::updateLight(float intensity, float* colorCorrection) {
    lightIntensity = intensity;
    if (colorCorrection) {
        lightColor[0] = colorCorrection[0]; lightColor[1] = colorCorrection[1]; lightColor[2] = colorCorrection[2];
    }
}

void MobileGS::processDepthData(uint8_t* depthBuffer, int width, int height) {
    if (!depthBuffer || width <= 0 || height <= 0) return;

    std::lock_guard<std::mutex> lock(pointMutex);

    float fx = projMtx[0] * (width / 2.0f);
    float fy = projMtx[5] * (height / 2.0f);
    float cx = width / 2.0f;
    float cy = height / 2.0f;

    int subsample = 8;
    for (int y = 0; y < height; y += subsample) {
        for (int x = 0; x < width; x += subsample) {
            int index = (y * width + x) * 2;
            uint16_t d16 = depthBuffer[index] | (depthBuffer[index + 1] << 8);
            if (d16 == 0 || d16 > 6500) continue;

            float z = d16 / 1000.0f;
            float px = (x - cx) * z / fx;
            float py = (y - cy) * z / fy;

            float wx = px * viewMtx[0] + py * viewMtx[1] + z * viewMtx[2] + viewMtx[3];
            float wy = px * viewMtx[4] + py * viewMtx[5] + z * viewMtx[6] + viewMtx[7];
            float wz = px * viewMtx[8] + py * viewMtx[9] + z * viewMtx[10] + viewMtx[11];

            VoxelKey key = { (int)(wx / VOXEL_SIZE), (int)(wy / VOXEL_SIZE), (int)(wz / VOXEL_SIZE) };

            if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
                mVoxelGrid[key] = { wx, wy, wz, 1.0f, 1.0f, 1.0f, 1.0f, 0.1f };
            } else {
                mVoxelGrid[key].confidence = std::min(1.0f, mVoxelGrid[key].confidence + 0.05f);
            }
        }
    }
}

void MobileGS::processMonocularData(uint8_t* imageData, int width, int height) {
    if (!imageData || width <= 0 || height <= 0) return;

    cv::Mat img(height, width, CV_8UC1, imageData);
    std::vector<cv::KeyPoint> keypoints;
    cv::Ptr<cv::FeatureDetector> detector = cv::FastFeatureDetector::create(40);
    detector->detect(img, keypoints);

    if (keypoints.empty()) return;

    std::lock_guard<std::mutex> lock(pointMutex);

    float fx = projMtx[0] * (width / 2.0f);
    float fy = projMtx[5] * (height / 2.0f);
    float cx = width / 2.0f;
    float cy = height / 2.0f;

    float z = 1.5f;

    for (const auto& kp : keypoints) {
        float px = (kp.pt.x - cx) * z / fx;
        float py = (kp.pt.y - cy) * z / fy;

        float wx = px * viewMtx[0] + py * viewMtx[1] + z * viewMtx[2] + viewMtx[3];
        float wy = px * viewMtx[4] + py * viewMtx[5] + z * viewMtx[6] + viewMtx[7];
        float wz = px * viewMtx[8] + py * viewMtx[9] + z * viewMtx[10] + viewMtx[11];

        VoxelKey key = { (int)(wx / VOXEL_SIZE), (int)(wy / VOXEL_SIZE), (int)(wz / VOXEL_SIZE) };

        if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
            if (mVoxelGrid.size() >= MAX_VOXELS) {
                mVoxelGrid.clear();
            }
            float r = (sin(wx) + 1.0f) * 0.5f;
            float g = (cos(wy) + 1.0f) * 0.5f;
            mVoxelGrid[key] = { wx, wy, wz, r, g, 1.0f, 1.0f, 0.1f };
        } else {
            mVoxelGrid[key].confidence = std::min(1.0f, mVoxelGrid[key].confidence + 0.1f);
        }
    }
}

void MobileGS::addStereoPoints(const std::vector<cv::Point3f>& points) {
    std::lock_guard<std::mutex> lock(pointMutex);
    for (const auto& p : points) {
        VoxelKey key = { (int)(p.x / VOXEL_SIZE), (int)(p.y / VOXEL_SIZE), (int)(p.z / VOXEL_SIZE) };
        if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
            mVoxelGrid[key] = { p.x, p.y, p.z, 0.0f, 1.0f, 1.0f, 1.0f, 0.1f };
        }
    }
}

void MobileGS::setVisualizationMode(int mode) { visMode = mode; }

bool MobileGS::saveMap(const char* path) {
    std::lock_guard<std::mutex> lock(pointMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;

    out.write("GXRM", 4);
    int version = 1;
    out.write((char*)&version, 4);

    int splatCount = mVoxelGrid.size();
    out.write((char*)&splatCount, 4);

    int keyframeCount = 1;
    out.write((char*)&keyframeCount, 4);

    for (const auto& pair : mVoxelGrid) {
        out.write((char*)&pair.second, sizeof(SplatPoint));
    }

    out.write((char*)alignmentMtx, 16 * sizeof(float));

    out.close();
    LOGI("Successfully saved map with %d splats.", splatCount);
    return true;
}

bool MobileGS::loadMap(const char* path) {
    std::lock_guard<std::mutex> lock(pointMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;

    char magic[4];
    in.read(magic, 4);
    if (strncmp(magic, "GXRM", 4) != 0) return false;

    int version, splatCount, keyframeCount;
    in.read((char*)&version, 4);
    in.read((char*)&splatCount, 4);
    in.read((char*)&keyframeCount, 4);

    mVoxelGrid.clear();
    for (int i = 0; i < splatCount; i++) {
        SplatPoint p;
        in.read((char*)&p, sizeof(SplatPoint));
        VoxelKey key = { (int)(p.x / VOXEL_SIZE), (int)(p.y / VOXEL_SIZE), (int)(p.z / VOXEL_SIZE) };
        mVoxelGrid[key] = p;
    }

    if (keyframeCount > 0) {
        in.read((char*)alignmentMtx, 16 * sizeof(float));
    }

    in.close();
    LOGI("Successfully loaded map with %d splats.", splatCount);
    return true;
}

bool MobileGS::saveKeyframe(const char* path) {
    std::ofstream out(path, std::ios::binary);
    if (!out) {
        LOGE("Failed to open keyframe path: %s", path);
        return false;
    }

    out.write((char*)viewMtx, 16 * sizeof(float));
    out.write((char*)projMtx, 16 * sizeof(float));

    time_t now = time(0);
    out.write((char*)&now, sizeof(time_t));

    out.close();
    LOGI("Saved keyframe to %s", path);
    return true;
}