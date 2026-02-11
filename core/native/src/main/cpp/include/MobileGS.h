#ifndef MOBILE_GS_H
#define MOBILE_GS_H

#include <vector>
#include <string>
#include <unordered_map>
#include <mutex>
#include <cmath>
#include <GLES3/gl3.h>
#include <glm/glm.hpp>

struct Splat {
    float x, y, z;
    float nx, ny, nz;
    float radius;
    uint8_t r, g, b;
    float confidence;
    uint32_t lastSeenFrame;
    float luminance;
};

struct ChunkKey {
    int x, y, z;
    bool operator==(const ChunkKey& other) const {
        return x == other.x && y == other.y && z == other.z;
    }
};

struct ChunkKeyHash {
    std::size_t operator()(const ChunkKey& k) const {
        size_t h1 = std::hash<int>{}(k.x);
        size_t h2 = std::hash<int>{}(k.y);
        size_t h3 = std::hash<int>{}(k.z);
        return h1 ^ (h2 << 1) ^ (h3 << 2);
    }
};

struct Chunk {
    bool isDirty;
    bool isActive;
    std::vector<Splat> splats;
    GLuint vbo;
    int splatCount;

    Chunk() : isDirty(true), isActive(true), vbo(0), splatCount(0) {}
};

class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    // --- Core Pipeline (SplaTAM) ---
    // CHANGED: depthPixels is now uint16_t* (raw millimeters)
    void feedDepthData(const uint16_t* depthPixels, const float* colorPixels,
            int width, int height, const float* cameraPose, float fov);

    void update(const float* cameraPose);
    void render(const float* viewMatrix, const float* projMatrix);

    void initialize();
    void updateCamera(const float* view, const float* proj);
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

    ChunkKey getChunkKey(float x, float y, float z);
    float getLuminance(uint8_t r, uint8_t g, uint8_t b);
    void fuseSplat(Splat& target, const Splat& source);
};

#endif // MOBILE_GS_H