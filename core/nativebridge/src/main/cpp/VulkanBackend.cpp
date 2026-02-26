#include "include/VulkanBackend.h"

VulkanBackend::VulkanBackend() {}

VulkanBackend::~VulkanBackend() {
    destroy();
}

bool VulkanBackend::initialize(ANativeWindow* window, AAssetManager* assetManager) {
    m_window = window;
    return true;
}

void VulkanBackend::destroy() {
    if (m_window) {
        ANativeWindow_release(m_window);
        m_window = nullptr;
    }
}

void VulkanBackend::renderFrame() {}

void VulkanBackend::updateCamera(const float* viewMatrix, const float* projectionMatrix) {}

void VulkanBackend::setLighting(float intensity, const float* colorCorrection) {}

void VulkanBackend::resize(int width, int height) {}