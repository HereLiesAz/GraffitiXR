#pragma once
#include <opencv2/opencv.hpp>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <GLES3/gl3.h>
#include <glm/glm.hpp>
#include <glm/gtc/type_ptr.hpp>

struct Splat {
    float x, y, z;          // Mean (Position)
    float r, g, b, a;       // Color (SH base) and Opacity
    float sh[9];            // Spherical Harmonics (Level 1, view-dependent color)
    float scale[3];         // Scaling factors (Anisotropic)
    float rot[4];           // Rotation (Quaternion)
    float confidence;       // Persistence/Certainty
    float velocity[3];      // [DEBLUR] Linear velocity for motion compensation
};

struct Keyframe {
    cv::Mat depth;
    cv::Mat color;
    float viewMatrix[16];
    float projMatrix[16];
    float angularVelocity[3]; // [DEBLUR] From IMU
    float linearVelocity[3];  // [DEBLUR] From VIO/IMU
};

struct VoxelKey {
    int x, y, z;
    bool operator==(const VoxelKey& other) const {
        return x == other.x && y == other.y && z == other.z;
    }
};

struct VoxelKeyHash {
    std::size_t operator()(const VoxelKey& k) const {
        return ((std::hash<int>()(k.x) ^ (std::hash<int>()(k.y) << 1)) >> 1) ^ (std::hash<int>()(k.z) << 1);
    }
};

struct ProtoSplat {
    float x, y, z;
};

class VoxelHash {
public:
    VoxelHash();
    ~VoxelHash();

    void initGl();
    void update(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, float voxelSize, float lightLevel);
    void addSparsePoints(const std::vector<float>& points, const float* viewMat, const float* projMat);
    void addKeyframe(const Keyframe& kf);
    void draw(const glm::mat4& mvp, const glm::mat4& view, float focalY, int screenHeight);
    void clear();
    void prune(float threshold);
    void save(const std::string& path);
    void load(const std::string& path);
    int getSplatCount() const;
    int getImmutableSplatCount() const;
    void optimize(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat);
    float getVisibleConfidenceAvg() const;
    float getGlobalConfidenceAvg() const;

private:
    void pruneInternal(float threshold, float voxelSize);

    mutable std::mutex mMutex;
    std::vector<Splat> mSplatData;
    std::vector<ProtoSplat> mProtoSplats;
    std::vector<Keyframe> mKeyframes;
    std::unordered_map<VoxelKey, int, VoxelKeyHash> mVoxelGrid;

    float mLastVoxelSize = 0.005f;
    GLuint mProgram = 0;
    GLuint mPointVbo = 0;
    GLuint mQuadVbo = 0;
    GLuint mVao = 0;
    bool mDataDirty = false;
    int mNextRefineIndex = 0;

    static constexpr int MAX_SPLATS = 500000;
};
