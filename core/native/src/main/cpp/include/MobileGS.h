#ifndef MOBILE_GS_H
#define MOBILE_GS_H

#include <vector>
#include <string>
#include <unordered_map>
#include <mutex>
#include <cmath>
#include <GLES3/gl3.h>
#include <glm/glm.hpp>

// --- SPLATAM: Augmented Data Structure ---
struct Splat {
    // Spatial (Geometry)
    float x, y, z;

    // Orientation & Scale (Covariance approximation for Mobile)
    float nx, ny, nz; // Surface Normal
    float radius;     // Splat influence radius

    // Visual (Appearance)
    uint8_t r, g, b;  // Base Albedo

    // Metadata
    float confidence;       // 0.0 to 1.0
    uint32_t lastSeenFrame; // For culling/maintenance
    float luminance;        // Cached for lighting invariance
};

// --- DISKCHUNGS: Spatial Hashing ---
struct ChunkKey {
    int x, y, z;

    bool operator==(const ChunkKey& other) const {
        return x == other.x && y == other.y && z == other.z;
    }
};

struct ChunkKeyHash {
    std::size_t operator()(const ChunkKey& k) const {
        // Cantor pairing or simple XOR hash for 3D coords
        size_t h1 = std::hash<int>{}(k.x);
        size_t h2 = std::hash<int>{}(k.y);
        size_t h3 = std::hash<int>{}(k.z);
        return h1 ^ (h2 << 1) ^ (h3 << 2);
    }
};

struct Chunk {
    bool isDirty;       // Needs GL buffer update
    bool isActive;      // Is currently in memory/renderable
    std::vector<Splat> splats;

    // GL Buffers for this chunk
    GLuint vbo;
    int splatCount;

    Chunk() : isDirty(true), isActive(true), vbo(0), splatCount(0) {}
};

class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    // --- Core Pipeline (SplaTAM) ---
    void feedDepthData(const float* depthPixels, const float* colorPixels,
            int width, int height, const float* cameraPose, float fov);

    void update(const float* cameraPose); // Manages chunks (load/unload)

    // Renders using stored matrices or passed ones
    void render(const float* viewMatrix, const float* projMatrix);

    // --- JNI Adapter Methods ---
    void initialize(); // Reset/Init
    void updateCamera(const float* view, const float* proj); // Stores matrices for draw()
    void draw(); // Calls render() with stored matrices

    void onSurfaceChanged(int width, int height);
    int getSplatCount();
    void clear();

    // Serialization
    bool saveModel(std::string path);
    bool loadModel(std::string path);
    void alignMap(const float* transform);

    // Settings
    void setChunkSize(float meters) { mChunkSize = meters; }

private:
    // Parameters
    float mChunkSize = 2.0f;
    float mVoxelSize = 0.05f;
    int mFrameCount = 0;

    // State
    glm::mat4 mStoredView;
    glm::mat4 mStoredProj;
    int mScreenWidth, mScreenHeight;

    // Storage
    std::unordered_map<ChunkKey, Chunk, ChunkKeyHash> mChunks;
    std::mutex mChunkMutex;

    // Helpers
    ChunkKey getChunkKey(float x, float y, float z);
    float getLuminance(uint8_t r, uint8_t g, uint8_t b);
    void fuseSplat(Splat& target, const Splat& source);
};

#endif // MOBILE_GS_H