#ifndef MOBILE_GS_H
#define MOBILE_GS_H

#include <vector>
#include <mutex>
#include <memory>
#include <unordered_map>
#include <string>
#include <cmath>

// OpenCV
#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>

// OpenGL / GLM
#include <GLES3/gl3.h>
#include <glm/glm.hpp>

/**
 * Represents a single Gaussian Splat (or point) in the 3D world.
 */
struct Splat {
    float x, y, z;        ///< Position in World Space
    float nx, ny, nz;     ///< Normal vector (unused currently)
    float radius;         ///< Radius of the splat
    uint8_t r, g, b;      ///< Color (RGB 0-255)
    float confidence;     ///< Confidence score of the point (0.0-1.0)
    uint32_t lastSeenFrame; ///< Frame index when this point was last updated
    float luminance;      ///< Calculated luminance for culling
};

/**
 * Key for the spatial hash map (Chunk System).
 */
struct ChunkKey {
    int x, y, z;
    bool operator==(const ChunkKey& other) const {
        return x == other.x && y == other.y && z == other.z;
    }
};

/**
 * Hash function for ChunkKey.
 */
struct ChunkKeyHash {
    std::size_t operator()(const ChunkKey& k) const {
        size_t h1 = std::hash<int>{}(k.x);
        size_t h2 = std::hash<int>{}(k.y);
        size_t h3 = std::hash<int>{}(k.z);
        return h1 ^ (h2 << 1) ^ (h3 << 2);
    }
};

/**
 * A voxel chunk containing a subset of the point cloud.
 * Used for efficient rendering and culling.
 */
struct Chunk {
    bool isDirty;           ///< True if VBO needs to be updated
    bool isActive;          ///< True if chunk is visible
    std::vector<Splat> splats; ///< List of points in this chunk
    GLuint vbo;             ///< OpenGL Vertex Buffer Object ID
    int splatCount;         ///< Number of splats

    Chunk() : isDirty(true), isActive(true), vbo(0), splatCount(0) {}
};

/**
 * The core C++ engine for MobileGS (Mobile Gaussian Splatting / SplaTAM).
 * Handles SLAM mapping (Teleological), point cloud fusion (Splatting), and OpenGL rendering.
 */
class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    void Initialize(int width, int height);
    void Cleanup();

    // --- Teleological SLAM (OpenCV) ---
    void Update(const cv::Mat& cameraFrame, const float* viewMatrix, const float* projectionMatrix);
    void SetTargetDescriptors(const cv::Mat& descriptors);
    bool IsTrackingTarget() const;

    // --- Gaussian Splatting (OpenGL) ---
    /**
     * Ingests a new depth frame from ARCore and fuses it into the map.
     */
    void feedDepthData(const uint16_t* depthPixels, const float* colorPixels,
            int width, int height, int stride, const float* cameraPose, float fov);

    void updateCamera(const float* view, const float* proj);
    void draw();
    void onSurfaceChanged(int width, int height);
    int getSplatCount();
    void clear();

    bool saveModel(std::string path);
    bool loadModel(std::string path);
    void alignMap(const float* transform);

private:
    int mWidth;
    int mHeight;
    bool mIsInitialized;

    // --- Teleological State ---
    cv::Ptr<cv::ORB> mOrb;
    cv::Ptr<cv::DescriptorMatcher> mMatcher;
    std::vector<cv::KeyPoint> mMapKeypoints; // 2D (Legacy/Debug)
    std::vector<cv::Point3f> mMapPoints3D;   // 3D World Points (Sparse)
    cv::Mat mMapDescriptors;
    std::mutex mMapMutex;
    cv::Mat mTargetDescriptors;
    bool mHasTarget;

    // Matrices (Shared)
    float mViewMatrix[16];
    float mProjMatrix[16];

    // --- Splatting State ---
    float mChunkSize = 2.0f;
    float mVoxelSize = 0.05f;
    int mFrameCount = 0;
    glm::mat4 mStoredView;
    glm::mat4 mStoredProj;
    int mScreenWidth, mScreenHeight;
    std::unordered_map<ChunkKey, Chunk, ChunkKeyHash> mChunks;
    std::mutex mChunkMutex;
    GLuint mProgram = 0;
    GLint mLocMVP = -1;
    GLint mLocPointSize = -1;

    // --- Internal Methods ---
    void ProcessFrame(const cv::Mat& frame);
    void MatchAndFuse(const std::vector<cv::KeyPoint>& keypoints, const cv::Mat& descriptors);
    cv::Point2f ProjectPoint(const cv::Point3f& p3d);
    cv::Point3f UnprojectPoint(const cv::KeyPoint& kpt, float depth);
    void MultiplyMatrixVector(const float* matrix, const float* in, float* out);

    ChunkKey getChunkKey(float x, float y, float z);
    float getLuminance(uint8_t r, uint8_t g, uint8_t b);
};

#endif // MOBILE_GS_H
