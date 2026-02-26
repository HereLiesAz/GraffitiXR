#ifndef GRAFFITIXR_SLAM_ENGINE_H
#define GRAFFITIXR_SLAM_ENGINE_H

#include <vector>
#include <cstdint>

/**
 * Singleton engine coordinating SLAM and Stereo Depth processing.
 */
class SlamEngine {
public:
    static SlamEngine* getInstance() {
        static SlamEngine instance;
        return &instance;
    }

    void processStereo(int8_t* leftData, int8_t* rightData, int width, int height) {
        // Implementation will interface with StereoProcessor.cpp
    }

    void initialize() {}
    void destroy() {}

private:
    SlamEngine() = default;
    ~SlamEngine() = default;
    SlamEngine(const SlamEngine&) = delete;
    SlamEngine& operator=(const SlamEngine&) = delete;
};

#endif // GRAFFITIXR_SLAM_ENGINE_H