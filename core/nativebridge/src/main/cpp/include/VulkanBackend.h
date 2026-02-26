#ifndef GRAFFITIXR_VULKANBACKEND_H
#define GRAFFITIXR_VULKANBACKEND_H

#include <android/native_window.h>
#include <android/asset_manager.h>

// Enable Android Vulkan extensions
#define VK_USE_PLATFORM_ANDROID_KHR
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
    void setLighting(float intensity, float* color);

private:
    // Internal Setup Helpers
    bool createInstance();
    bool createSurface();
    bool selectPhysicalDevice();
    bool createLogicalDevice();
    bool createSwapchain();
    bool createImageViews();
    bool createRenderPass();
    bool createPipeline(); // Placeholder for future expansion
    bool createFramebuffers();
    bool createCommandPool();
    bool createCommandBuffers();
    bool createSyncObjects();

    void recordCommandBuffer(VkCommandBuffer commandBuffer, uint32_t imageIndex);
    void cleanupSwapchain();
    void recreateSwapchain();

    // State Variables
    ANativeWindow* window = nullptr;
    AAssetManager* assetManager = nullptr;
    int width = 0;
    int height = 0;

    // Lighting State
    float lightIntensity = 1.0f;
    float lightColor[3] = {1.0f, 1.0f, 1.0f};

    // Vulkan Handles
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;

    // Queues
    VkQueue graphicsQueue = VK_NULL_HANDLE;
    VkQueue presentQueue = VK_NULL_HANDLE;
    uint32_t graphicsQueueFamilyIndex = -1;

    // Swapchain
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    std::vector<VkImage> swapchainImages;
    std::vector<VkImageView> swapchainImageViews;
    std::vector<VkFramebuffer> swapchainFramebuffers;
    VkFormat swapchainImageFormat;
    VkExtent2D swapchainExtent;

    // Pipeline
    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline graphicsPipeline = VK_NULL_HANDLE;

    // Commands & Sync
    VkCommandPool commandPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> commandBuffers;

    VkSemaphore imageAvailableSemaphore = VK_NULL_HANDLE;
    VkSemaphore renderFinishedSemaphore = VK_NULL_HANDLE;
    VkFence inFlightFence = VK_NULL_HANDLE;
};

#endif // GRAFFITIXR_VULKANBACKEND_H