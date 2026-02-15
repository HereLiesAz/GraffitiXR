#ifndef GRAFFITIXR_VULKANBACKEND_H
#define GRAFFITIXR_VULKANBACKEND_H

#include <android/native_window.h>
#include <android/asset_manager.h>
#include <vulkan/vulkan.h>
#include <vector>

class VulkanBackend {
public:
    VulkanBackend();
    ~VulkanBackend();

    // Lifecycle
    bool initialize(ANativeWindow* nativeWindow, AAssetManager* assetManager);
    void resize(int width, int height);
    void destroy();

    // Rendering
    void renderFrame();

    // Data Updates
    void updateCamera(float* viewMatrix, float* projectionMatrix);
    void updateModel(float* modelMatrix);

private:
    // Internal Setup Helpers
    bool createInstance();
    bool createSurface();
    bool selectPhysicalDevice();
    bool createLogicalDevice();
    bool createSwapchain();
    bool createRenderPass();
    bool createPipeline();
    bool createFramebuffers();
    bool createCommandPool();
    bool createCommandBuffers();
    bool createSyncObjects();

    void cleanupSwapchain();
    void recreateSwapchain();

    // State Variables
    ANativeWindow* window = nullptr;
    AAssetManager* assetManager = nullptr;
    int width = 0;
    int height = 0;

    // Vulkan Handles
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;

    // Swapchain
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    std::vector<VkFramebuffer> swapchainFramebuffers;

    // Pipeline
    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> commandBuffers;
};

#endif // GRAFFITIXR_VULKANBACKEND_H