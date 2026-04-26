#pragma once
#include <opencv2/opencv.hpp>
#include <vector>
#include <unordered_map>
#include <unordered_set>
#include <mutex>
#include <GLES3/gl3.h>
#include <glm/glm.hpp>
#include <glm/gtc/type_ptr.hpp>

// Dense Opaque Surfel Structure
struct Splat {
    float x, y, z;          // Position (Mean): Offset 0
    float r, g, b, a;       // Color and Opacity (Opaque): Offset 12
    float sh[9];            // Spherical Harmonics (Level 1): Offsets 28, 40, 52
    float scale[3];         // Anisotropic scale: Offset 64
    float rot[4];           // Rotation quaternion: Offset 76
    float confidence;       // Observation count: Offset 92
    float velocity[3];      // Motion vector: Offset 96

    // Gradient tracking for adaptive densification (Not sent to GPU)
    float gradAccum;
    int gradCount;
};

struct Keyframe {
    cv::Mat depth;
    cv::Mat color;
    float viewMatrix[16];
    float projMatrix[16];
    float angularVelocity[3];
    float linearVelocity[3];
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

class VoxelHash {
public:
    VoxelHash();
    ~VoxelHash();

    void initGl();
    void update(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, float voxelSize, float initialConfidence);
    void addSparsePoints(const std::vector<float>& points, const float* viewMat, const float* projMat);
    void addKeyframe(const Keyframe& kf);
    void draw(const glm::mat4& mvp, const glm::mat4& view, float focalY, int screenHeight);
    void sort(const glm::vec3& camPos);
    void densify(float threshold, float scaleLimit);
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
    std::vector<Keyframe> mKeyframes;
    std::unordered_map<VoxelKey, int, VoxelKeyHash> mVoxelGrid;

    std::vector<glm::vec3> mProtoSplats;
    std::unordered_set<VoxelKey, VoxelKeyHash> mProtoGrid;

    float mLastVoxelSize = 0.02f;
    GLuint mProgram = 0;
    GLuint mPointVbo = 0;
    GLuint mQuadVbo = 0;
    GLuint mVao = 0;
    bool mDataDirty = false;
    int mNextRefineIndex = 0;

    static constexpr int MAX_SPLATS = 500000;
};
