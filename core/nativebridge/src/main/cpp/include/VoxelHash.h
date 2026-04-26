#pragma once
#include <opencv2/opencv.hpp>
#include <vector>
#include <mutex>
#include <GLES3/gl3.h>
#include <glm/glm.hpp>
#include <glm/gtc/type_ptr.hpp>

// High-Performance Voxel Memory Structure
struct Splat {
    float x, y, z;          // Position (World Space)
    float r, g, b, a;       // Color and Rendering Opacity
    float nx, ny, nz;       // Surface Normal
    float confidence;       // Observation stability
};

struct VoxelFrame {
    cv::Mat depth;
    cv::Mat color;
    float viewMatrix[16];
    float projMatrix[16];
};

class VoxelHash {
public:
    VoxelHash();
    ~VoxelHash();

    void initGl();
    void update(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, float voxelSize, float initialConfidence);
    void addSparsePoints(const std::vector<float>& points, const float* viewMat, const float* projMat, float initialConfidence);
    void addKeyframe(const VoxelFrame& kf);
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
    uint32_t getVoxelHash(float x, float y, float z, float voxelSize);

    mutable std::mutex mMutex;
    std::vector<Splat> mSplatData;
    std::vector<VoxelFrame> mRecentFrames;

    // Fixed-size spatial hash table (Zero-allocation during tracking)
    static constexpr int HASH_SIZE = 262144; // 2^18
    int32_t mSpatialHash[HASH_SIZE];

    float mLastVoxelSize = 0.02f;
    GLuint mProgram = 0;
    GLuint mPointVbo = 0;
    bool mDataDirty = false;
    size_t mLastUploadCount = 0;
    int64_t mLastUploadTimeMs = 0;

    static constexpr int MAX_SPLATS = 250000; // Optimal balance for persistence
};
