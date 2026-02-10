#include "MobileGS.h"
#include <cmath>
#include <algorithm>
#include <glm/glm.hpp>
#include <glm/gtc/matrix_transform.hpp>
#include <glm/gtc/type_ptr.hpp>

// Helper to unpack Pose
glm::mat4 makeMat4(const float* d) {
    return glm::make_mat4(d);
}

MobileGS::MobileGS() {
    mChunkSize = 2.0f;
    mVoxelSize = 0.05f; // 5cm voxels
}

MobileGS::~MobileGS() {
    for (auto& pair : mChunks) {
        if (pair.second.vbo != 0) {
            glDeleteBuffers(1, &pair.second.vbo);
        }
    }
}

ChunkKey MobileGS::getChunkKey(float x, float y, float z) {
    return ChunkKey{
            (int)floor(x / mChunkSize),
            (int)floor(y / mChunkSize),
            (int)floor(z / mChunkSize)
    };
}

float MobileGS::getLuminance(uint8_t r, uint8_t g, uint8_t b) {
    // Standard Rec. 601 Luma
    return 0.299f * r + 0.587f * g + 0.114f * b;
}

// --- CORE FUSION LOGIC (SplaTAM + Illumination Invariance) ---
void MobileGS::fuseSplat(Splat& target, const Splat& source) {
    // 1. ILLUMINATION INVARIANCE ("Taming the Light")
    // If the geometry matches but luminance is wildly different, it's likely a shadow/highlight.
    float lumDiff = std::abs(target.luminance - source.luminance);
    float lumThreshold = 50.0f; // out of 255

    if (lumDiff > lumThreshold) {
        // Reject Color Update: Maintain old albedo (assume it's the true surface color)
        // But Update Geometry: The surface is still there.
        // We only slightly boost confidence to show we saw *something*.
        target.confidence = std::min(target.confidence + 0.05f, 1.0f);
        target.lastSeenFrame = source.lastSeenFrame;
        return;
    }

    // 2. STANDARD FUSION (Moving Average)
    float alpha = 0.1f; // Learning rate

    // Geometry
    target.x = target.x * (1.0f - alpha) + source.x * alpha;
    target.y = target.y * (1.0f - alpha) + source.y * alpha;
    target.z = target.z * (1.0f - alpha) + source.z * alpha;

    // SplaTAM: Update Normal and Radius
    target.nx = target.nx * (1.0f - alpha) + source.nx * alpha;
    target.ny = target.ny * (1.0f - alpha) + source.ny * alpha;
    target.nz = target.nz * (1.0f - alpha) + source.nz * alpha;

    // Normalize normal
    float invLen = 1.0f / sqrt(target.nx*target.nx + target.ny*target.ny + target.nz*target.nz);
    target.nx *= invLen; target.ny *= invLen; target.nz *= invLen;

    target.radius = target.radius * (1.0f - alpha) + source.radius * alpha;

    // Color
    target.r = (uint8_t)(target.r * (1.0f - alpha) + source.r * alpha);
    target.g = (uint8_t)(target.g * (1.0f - alpha) + source.g * alpha);
    target.b = (uint8_t)(target.b * (1.0f - alpha) + source.b * alpha);

    // Update cached luminance for next check
    target.luminance = getLuminance(target.r, target.g, target.b);

    target.confidence = std::min(target.confidence + 0.2f, 1.0f);
    target.lastSeenFrame = source.lastSeenFrame;
}

void MobileGS::feedDepthData(const float* depthPixels, const float* colorPixels,
        int width, int height, const float* cameraPose, float fov) {
    mFrameCount++;
    glm::mat4 pose = makeMat4(cameraPose);
    glm::vec3 camPos = glm::vec3(pose[3]);
    glm::vec3 camFwd = glm::vec3(pose[2]) * -1.0f; // -Z is forward in GL

    std::lock_guard<std::mutex> lock(mChunkMutex);

    // Stride to save perf (process every 4th pixel)
    int stride = 4;
    float fx = (width / 2.0f) / tan(fov / 2.0f);
    float fy = fx;
    float cx = width / 2.0f;
    float cy = height / 2.0f;

    for (int v = 0; v < height; v += stride) {
        for (int u = 0; u < width; u += stride) {
            int idx = v * width + u;
            float d = depthPixels[idx];

            if (d <= 0.1f || d > 4.0f) continue; // Ignore sky/noise

            // Back-project
            float z_cam = -d;
            float x_cam = (u - cx) * d / fx;
            float y_cam = (v - cy) * d / fy;

            glm::vec4 worldPos = pose * glm::vec4(x_cam, y_cam, z_cam, 1.0f);

            // Create Candidate Splat
            Splat s;
            s.x = worldPos.x;
            s.y = worldPos.y;
            s.z = worldPos.z;

            // SplaTAM Heuristic: Normal faces camera initially
            // (Real SplaTAM uses depth gradients, this is the fast mobile approx)
            glm::vec3 dirToCam = glm::normalize(camPos - glm::vec3(s.x, s.y, s.z));
            s.nx = dirToCam.x;
            s.ny = dirToCam.y;
            s.nz = dirToCam.z;

            // Radius grows with distance (perspective projected size)
            s.radius = d * (stride / fx) * 1.5f; // 1.5x overlap factor

            // Color
            // Assume colorPixels is RGB float 0..1 or 0..255. Assuming 0..1 from Bitmap
            // Adjust index if color buffer has different stride/layout
            s.r = (uint8_t)(colorPixels[idx * 3 + 0] * 255.0f);
            s.g = (uint8_t)(colorPixels[idx * 3 + 1] * 255.0f);
            s.b = (uint8_t)(colorPixels[idx * 3 + 2] * 255.0f);
            s.luminance = getLuminance(s.r, s.g, s.b);
            s.confidence = 0.5f;
            s.lastSeenFrame = mFrameCount;

            // Find Chunk
            ChunkKey key = getChunkKey(s.x, s.y, s.z);
            Chunk& chunk = mChunks[key]; // Auto-creates if missing

            // Find existing voxel in chunk (Linear search is slow, but fine for small chunks + stride)
            // OPTIMIZATION: Spatial Hash inside chunk could go here.
            bool found = false;
            float mergeDistSq = mVoxelSize * mVoxelSize;

            for (auto& existing : chunk.splats) {
                float dx = existing.x - s.x;
                float dy = existing.y - s.y;
                float dz = existing.z - s.z;
                if (dx*dx + dy*dy + dz*dz < mergeDistSq) {
                    fuseSplat(existing, s);
                    found = true;
                    break;
                }
            }

            if (!found) {
                chunk.splats.push_back(s);
            }
            chunk.isDirty = true;
        }
    }
}

void MobileGS::update(const float* cameraPose) {
    // DiskChunGS Logic: Unload far chunks
    // For now, we just mark dirty buffers.
    // Real implementation would serialize mChunks to disk here.
}

void MobileGS::render(const float* viewMatrix, const float* projMatrix) {
    // Render all active chunks
    // Note: Use a shader that supports Splat size (gl_PointSize or Instancing)

    std::lock_guard<std::mutex> lock(mChunkMutex);

    for (auto& pair : mChunks) {
        Chunk& c = pair.second;
        if (c.splats.empty()) continue;

        if (c.isDirty) {
            if (c.vbo == 0) glGenBuffers(1, &c.vbo);

            // Pack data: X, Y, Z, R, G, B, Radius (7 floats/bytes mix)
            // Simplifying to struct dump for now.
            // Warning: Padding in struct Splat might mess this up.
            // Better to pack manually into a temp buffer.
            std::vector<float> buffer;
            buffer.reserve(c.splats.size() * 7);

            for (const auto& s : c.splats) {
                if (s.confidence < 0.3f) continue; // Noise filter
                buffer.push_back(s.x);
                buffer.push_back(s.y);
                buffer.push_back(s.z);
                buffer.push_back(s.r / 255.0f);
                buffer.push_back(s.g / 255.0f);
                buffer.push_back(s.b / 255.0f);
                buffer.push_back(s.radius); // Pass radius to shader
            }

            glBindBuffer(GL_ARRAY_BUFFER, c.vbo);
            glBufferData(GL_ARRAY_BUFFER, buffer.size() * sizeof(float), buffer.data(), GL_STATIC_DRAW);
            c.splatCount = buffer.size() / 7;
            c.isDirty = false;
        }

        if (c.splatCount > 0) {
            glBindBuffer(GL_ARRAY_BUFFER, c.vbo);

            // Pos
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)0);

            // Color
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)(3 * sizeof(float)));

            // Radius (Size)
            glEnableVertexAttribArray(2);
            glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)(6 * sizeof(float)));

            glDrawArrays(GL_POINTS, 0, c.splatCount);
        }
    }

    glBindBuffer(GL_ARRAY_BUFFER, 0);
}