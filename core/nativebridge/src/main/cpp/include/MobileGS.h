#ifndef MOBILE_GS_H
#define MOBILE_GS_H

#include <vector>
#include <string>
#include <unordered_map>
#include <mutex>
#include <cmath>
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
 * Handles SLAM mapping, point cloud fusion, and OpenGL rendering.
 */
class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    /**
     * Ingests a new depth frame from ARCore and fuses it into the map.
     *
     * @param depthPixels Pointer to 16-bit depth image buffer (millimeters).
     * @param colorPixels Pointer to color buffer (optional, can be null).
     * @param width Width of the depth image.
     * @param height Height of the depth image.
     * @param stride Row stride of the depth image in bytes.
     * @param cameraPose 4x4 Column-Major matrix representing camera pose in world space.
     * @param fov Vertical Field of View in radians.
     */
    void feedDepthData(const uint16_t* depthPixels, const float* colorPixels,
            int width, int height, int stride, const float* cameraPose, float fov);

    /**
     * Initializes the OpenGL context (shaders, buffers).
     * Must be called on the GL thread.
     */
    void initialize();

    /**
     * Updates the camera matrices for the next draw call.
     * @param view 4x4 View Matrix.
     * @param proj 4x4 Projection Matrix.
     */
    void updateCamera(const float* view, const float* proj);

    /**
     * Renders the point cloud to the currently bound framebuffer.
     */
    void draw();

    void onSurfaceChanged(int width, int height);
    int getSplatCount();
    void clear();

    bool saveModel(std::string path);
    bool loadModel(std::string path);
    void alignMap(const float* transform);

    void setChunkSize(float meters) { mChunkSize = meters; }

private:
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

    ChunkKey getChunkKey(float x, float y, float z);
};

#endif // MOBILE_GS_H