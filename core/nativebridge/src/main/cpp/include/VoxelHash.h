#pragma once
#include <opencv2/opencv.hpp>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <GLES3/gl3.h>
#include <glm/glm.hpp>
#include <glm/gtc/type_ptr.hpp>

// Real Gaussian Splat Structure (Mobile-Optimized)
struct Splat {
    float x, y, z;          // Position (Mean)
    float r, g, b, a;       // Color and Opacity
    float scale[3];         // 3D Scaling (Anisotropic)
    float rot[4];           // 3D Rotation (Quaternion)
    float confidence;       // Observation count / Quality
};

struct Keyframe {
    cv::Mat depth;
    cv::Mat color;
    float viewMatrix[16];
    float projMatrix[16];
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
    void addSparsePoints(const std::vector<float>& points, const float* viewMat, const float* projMat, float initialConfidence);
    void addKeyframe(const Keyframe& kf);
    void draw(const glm::mat4& mvp, const glm::mat4& view, float focalY, int screenHeight);
    void sort(const glm::vec3& camPos);
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

    float mLastVoxelSize = 0.02f;
    GLuint mProgram = 0;
    GLuint mPointVbo = 0;
    GLuint mQuadVbo = 0;
    GLuint mVao = 0;
    bool mDataDirty = false;
    int mNextRefineIndex = 0;

    static constexpr int MAX_SPLATS = 100000; // Appropriate cap for mobile sorting
};
