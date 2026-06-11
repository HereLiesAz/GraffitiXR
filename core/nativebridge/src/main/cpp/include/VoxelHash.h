#pragma once
#include <cstdint>
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
    // Parallax verification (appended after confidence so the GL vertex layout — offsets
    // 0/12/28/40, stride sizeof(Splat) — is unchanged; the shader reads none of these):
    float ox, oy, oz;       // Unit direction voxel→camera at first observation
    float parallaxDone;     // 0 until verified from a sufficiently different angle, then 1
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
    // Split GL init into program (shader compile/link) and buffer (11MB VBO) stages so a caller
    // can localize an init stall to the exact stage on-screen. initGl() runs both.
    void initGlProgram();
    void initGlBuffer();
    void update(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, float voxelSize, float initialConfidence);
    void addSparsePoints(const std::vector<float>& points, const float* viewMat, const float* projMat, float initialConfidence);
    void addKeyframe(const VoxelFrame& kf);
    // colorMode: 0 = normal opaque albedo, 1 = debug confidence ramp, 2 = coverage (alpha=confidence)
    void draw(const glm::mat4& mvp, const glm::mat4& view, float focalY, int screenHeight, int colorMode = 0);
    void sort(const glm::vec3& camPos);
    void clear();
    void prune(float threshold);
    /** Minimum angular baseline (degrees) before a re-observation counts as a parallax check. */
    void setParallaxMinDegrees(float deg) { mParallaxMinDeg = deg; }
    void save(const std::string& path);
    void load(const std::string& path);

    int getSplatCount() const;
    int getImmutableSplatCount() const;
    void getAnchorCandidates(std::vector<Splat>& out, float threshold, int maxCount) const;
    void optimize(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat);
    float getVisibleConfidenceAvg(const glm::mat4& mvp) const;
    float getGlobalConfidenceAvg() const;

private:
    uint32_t getVoxelHash(float x, float y, float z, float voxelSize);
    /** Clears all splat/keyframe/spatial-hash state. Caller MUST hold mMutex. Exists because
     *  load() needs to clear while already holding the (non-recursive) lock — load() calling the
     *  public clear() self-deadlocked the loading thread, which then wedged every other thread
     *  that touches the hash, including the GL render loop via MobileGS::mMutex. */
    void clearLocked();

    mutable std::mutex mMutex;
    float mParallaxMinDeg = 4.0f;  // default; overridden by the persisted Settings value
    // Disk-format identifiers for save/load. kSplatMagic is large enough that a legacy file's
    // leading count word can never collide with it, so the absence of the magic marks legacy data.
    static constexpr uint32_t kSplatMagic = 0x56584831u;   // "VXH1"
    static constexpr uint32_t kSplatVersion = 2u;          // 2 = parallax fields appended
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
