#include "MobileGS.h"
#include <cmath>
#include <algorithm>
#include <glm/glm.hpp>
#include <glm/gtc/matrix_transform.hpp>
#include <glm/gtc/type_ptr.hpp>
#include <fstream>
#include <iostream>

glm::mat4 makeMat4(const float* d) {
    return glm::make_mat4(d);
}

MobileGS::MobileGS() {
    mChunkSize = 2.0f;
    mVoxelSize = 0.05f;
    mScreenWidth = 1080;
    mScreenHeight = 1920;
    mStoredView = glm::mat4(1.0f);
    mStoredProj = glm::mat4(1.0f);
}

MobileGS::~MobileGS() {
    clear();
}

void MobileGS::initialize() {
    clear();
}

void MobileGS::clear() {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    for (auto& pair : mChunks) {
        if (pair.second.vbo != 0) {
            glDeleteBuffers(1, &pair.second.vbo);
        }
    }
    mChunks.clear();
    mFrameCount = 0;
}

void MobileGS::updateCamera(const float* view, const float* proj) {
    if (view) mStoredView = makeMat4(view);
    if (proj) mStoredProj = makeMat4(proj);
}

void MobileGS::draw() {
    render(glm::value_ptr(mStoredView), glm::value_ptr(mStoredProj));
}

void MobileGS::onSurfaceChanged(int width, int height) {
    mScreenWidth = width;
    mScreenHeight = height;
}

int MobileGS::getSplatCount() {
    int total = 0;
    std::lock_guard<std::mutex> lock(mChunkMutex);
    for (const auto& pair : mChunks) {
        total += pair.second.splats.size();
    }
    return total;
}

void MobileGS::alignMap(const float* transform) {
}

bool MobileGS::saveModel(std::string path) {
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;

    std::lock_guard<std::mutex> lock(mChunkMutex);
    size_t chunkCount = mChunks.size();
    out.write((char*)&chunkCount, sizeof(size_t));

    for (const auto& pair : mChunks) {
        const Chunk& c = pair.second;
        size_t splatCount = c.splats.size();
        out.write((char*)&splatCount, sizeof(size_t));
        if (splatCount > 0) {
            out.write((char*)c.splats.data(), splatCount * sizeof(Splat));
        }
    }
    out.close();
    return true;
}

bool MobileGS::loadModel(std::string path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;

    clear();

    size_t chunkCount;
    in.read((char*)&chunkCount, sizeof(size_t));

    for (size_t i = 0; i < chunkCount; i++) {
        size_t splatCount;
        in.read((char*)&splatCount, sizeof(size_t));
        if (splatCount > 0) {
            std::vector<Splat> tempSplats(splatCount);
            in.read((char*)tempSplats.data(), splatCount * sizeof(Splat));

            if (!tempSplats.empty()) {
                ChunkKey key = getChunkKey(tempSplats[0].x, tempSplats[0].y, tempSplats[0].z);
                mChunks[key].splats = tempSplats;
                mChunks[key].isDirty = true;
            }
        }
    }
    in.close();
    return true;
}

ChunkKey MobileGS::getChunkKey(float x, float y, float z) {
    return ChunkKey{
            (int)floor(x / mChunkSize),
            (int)floor(y / mChunkSize),
            (int)floor(z / mChunkSize)
    };
}

float MobileGS::getLuminance(uint8_t r, uint8_t g, uint8_t b) {
    return 0.299f * r + 0.587f * g + 0.114f * b;
}

void MobileGS::fuseSplat(Splat& target, const Splat& source) {
    float lumDiff = std::abs(target.luminance - source.luminance);
    float lumThreshold = 50.0f;

    if (lumDiff > lumThreshold) {
        target.confidence = std::min(target.confidence + 0.05f, 1.0f);
        target.lastSeenFrame = source.lastSeenFrame;
        return;
    }

    float alpha = 0.1f;

    target.x = target.x * (1.0f - alpha) + source.x * alpha;
    target.y = target.y * (1.0f - alpha) + source.y * alpha;
    target.z = target.z * (1.0f - alpha) + source.z * alpha;

    target.nx = target.nx * (1.0f - alpha) + source.nx * alpha;
    target.ny = target.ny * (1.0f - alpha) + source.ny * alpha;
    target.nz = target.nz * (1.0f - alpha) + source.nz * alpha;

    float invLen = 1.0f / sqrt(target.nx*target.nx + target.ny*target.ny + target.nz*target.nz + 0.0001f);
    target.nx *= invLen; target.ny *= invLen; target.nz *= invLen;

    target.radius = target.radius * (1.0f - alpha) + source.radius * alpha;

    target.r = (uint8_t)(target.r * (1.0f - alpha) + source.r * alpha);
    target.g = (uint8_t)(target.g * (1.0f - alpha) + source.g * alpha);
    target.b = (uint8_t)(target.b * (1.0f - alpha) + source.b * alpha);

    target.luminance = getLuminance(target.r, target.g, target.b);
    target.confidence = std::min(target.confidence + 0.2f, 1.0f);
    target.lastSeenFrame = source.lastSeenFrame;
}

// CHANGED: depthPixels type from float* to uint16_t*
void MobileGS::feedDepthData(const uint16_t* depthPixels, const float* colorPixels,
        int width, int height, const float* cameraPose, float fov) {
    mFrameCount++;
    glm::mat4 pose = makeMat4(cameraPose);
    glm::vec3 camPos = glm::vec3(pose[3]);

    std::lock_guard<std::mutex> lock(mChunkMutex);

    int stride = 4;
    float fx = (width / 2.0f) / tan(fov / 2.0f);
    float fy = fx;
    float cx = width / 2.0f;
    float cy = height / 2.0f;

    for (int v = 0; v < height; v += stride) {
        for (int u = 0; u < width; u += stride) {
            int idx = v * width + u;

            // CHANGED: Read uint16 raw depth (millimeters)
            uint16_t rawDepth = depthPixels[idx];

            // 0 means no data in ARCore Depth16
            if (rawDepth == 0) continue;

            // CHANGED: Convert mm to meters
            float d = (float)rawDepth * 0.001f;

            if (d <= 0.1f || d > 4.0f) continue;

            float z_cam = -d;
            float x_cam = (u - cx) * d / fx;
            float y_cam = (v - cy) * d / fy;

            glm::vec4 worldPos = pose * glm::vec4(x_cam, y_cam, z_cam, 1.0f);

            Splat s;
            s.x = worldPos.x;
            s.y = worldPos.y;
            s.z = worldPos.z;

            glm::vec3 dirToCam = glm::normalize(camPos - glm::vec3(s.x, s.y, s.z));
            s.nx = dirToCam.x; s.ny = dirToCam.y; s.nz = dirToCam.z;
            s.radius = d * (stride / fx) * 1.5f;

            if (colorPixels) {
                s.r = (uint8_t)(colorPixels[idx * 3 + 0] * 255.0f);
                s.g = (uint8_t)(colorPixels[idx * 3 + 1] * 255.0f);
                s.b = (uint8_t)(colorPixels[idx * 3 + 2] * 255.0f);
            } else {
                s.r = 255; s.g = 255; s.b = 255;
            }
            s.luminance = getLuminance(s.r, s.g, s.b);
            s.confidence = 0.5f;
            s.lastSeenFrame = mFrameCount;

            ChunkKey key = getChunkKey(s.x, s.y, s.z);
            Chunk& chunk = mChunks[key];

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

void MobileGS::update(const float* cameraPose) {}

void MobileGS::render(const float* viewMatrix, const float* projMatrix) {
    std::lock_guard<std::mutex> lock(mChunkMutex);

    for (auto& pair : mChunks) {
        Chunk& c = pair.second;
        if (c.splats.empty()) continue;

        if (c.isDirty) {
            if (c.vbo == 0) glGenBuffers(1, &c.vbo);

            std::vector<float> buffer;
            buffer.reserve(c.splats.size() * 7);

            for (const auto& s : c.splats) {
                if (s.confidence < 0.3f) continue;
                buffer.push_back(s.x);
                buffer.push_back(s.y);
                buffer.push_back(s.z);
                buffer.push_back(s.r / 255.0f);
                buffer.push_back(s.g / 255.0f);
                buffer.push_back(s.b / 255.0f);
                buffer.push_back(s.radius);
            }

            glBindBuffer(GL_ARRAY_BUFFER, c.vbo);
            glBufferData(GL_ARRAY_BUFFER, buffer.size() * sizeof(float), buffer.data(), GL_STATIC_DRAW);
            c.splatCount = buffer.size() / 7;
            c.isDirty = false;
        }

        if (c.splatCount > 0) {
            glBindBuffer(GL_ARRAY_BUFFER, c.vbo);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)(3 * sizeof(float)));
            glEnableVertexAttribArray(2);
            glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)(6 * sizeof(float)));
            glDrawArrays(GL_POINTS, 0, c.splatCount);
        }
    }
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}