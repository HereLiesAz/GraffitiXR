#include "VulkanBackend.h"
#include <android/log.h>
#include <vector>
#include <array>

#define TAG "VulkanBackend"
#if defined(NDEBUG)
#define LOGI(...)
#define LOGE(...)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#endif

VulkanBackend::VulkanBackend() {
    // Constructor
}

VulkanBackend::~VulkanBackend() {
    destroy();
}

bool VulkanBackend::initialize(ANativeWindow* nativeWindow, AAssetManager* mgr) {
    this->window = nativeWindow;
    this->assetManager = mgr;
    LOGI("Initializing Vulkan Backend...");

    if (!createInstance()) return false;
    if (!createSurface()) return false;
    if (!selectPhysicalDevice()) return false;
    if (!createLogicalDevice()) return false;
    // if (!createSwapchain()) return false; // Defer until we verify surface caps

    return true;
}

void VulkanBackend::resize(int w, int h) {
    this->width = w;
    this->height = h;
    LOGI("Resizing Vulkan Swapchain to %dx%d", w, h);
    recreateSwapchain();
}

void VulkanBackend::renderFrame() {
    // Stub: Vulkan draw commands would go here
    // In a real implementation, we would update the UBO with lightIntensity and lightColor here
}

void VulkanBackend::updateCamera(float* viewMatrix, float* projectionMatrix) {
    // Stub: Update uniform buffers
}

void VulkanBackend::updateModel(float* modelMatrix) {
    // Stub
}

void VulkanBackend::setLighting(float intensity, float* color) {
    lightIntensity = intensity;
    if (color) {
        lightColor[0] = color[0];
        lightColor[1] = color[1];
        lightColor[2] = color[2];
    }
}

void VulkanBackend::destroy() {
    LOGI("Destroying Vulkan Backend...");
    cleanupSwapchain();

    if (device != VK_NULL_HANDLE) {
        vkDestroyDevice(device, nullptr);
        device = VK_NULL_HANDLE;
    }

    if (surface != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(instance, surface, nullptr);
        surface = VK_NULL_HANDLE;
    }

    if (instance != VK_NULL_HANDLE) {
        vkDestroyInstance(instance, nullptr);
        instance = VK_NULL_HANDLE;
    }
}

// --- Implementation ---

bool VulkanBackend::createInstance() {
    VkApplicationInfo appInfo = {};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "GraffitiXR";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "MobileGS";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    std::vector<const char*> extensions = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
    };

    std::vector<const char*> layers;
    #ifndef NDEBUG
    layers.push_back("VK_LAYER_KHRONOS_validation");
    #endif

    VkInstanceCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    createInfo.ppEnabledExtensionNames = extensions.data();
    createInfo.enabledLayerCount = static_cast<uint32_t>(layers.size());
    createInfo.ppEnabledLayerNames = layers.data();

    VkResult result = vkCreateInstance(&createInfo, nullptr, &instance);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create Vulkan Instance: %d", result);
        return false;
    }
    LOGI("Vulkan Instance created successfully.");
    return true;
}

bool VulkanBackend::createSurface() {
    VkAndroidSurfaceCreateInfoKHR createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    createInfo.window = window;

    if (vkCreateAndroidSurfaceKHR(instance, &createInfo, nullptr, &surface) != VK_SUCCESS) {
        LOGE("Failed to create Android Surface");
        return false;
    }
    return true;
}

bool VulkanBackend::selectPhysicalDevice() {
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        LOGE("No Vulkan-compatible GPU found.");
        return false;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());

    // Simple selection: pick the first one suitable for graphics
    for (const auto& dev : devices) {
        // Check features here if needed
        physicalDevice = dev;
        break;
    }

    if (physicalDevice == VK_NULL_HANDLE) {
        LOGE("Failed to find suitable GPU.");
        return false;
    }
    return true;
}

bool VulkanBackend::createLogicalDevice() {
    // Find queue family
    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, nullptr);
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, queueFamilies.data());

    int graphicsFamily = -1;
    for (int i = 0; i < queueFamilies.size(); i++) {
        if (queueFamilies[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
            graphicsFamily = i;
            break;
        }
    }

    if (graphicsFamily == -1) {
        LOGE("Failed to find graphics queue family.");
        return false;
    }

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo = {};
    queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCreateInfo.queueFamilyIndex = graphicsFamily;
    queueCreateInfo.queueCount = 1;
    queueCreateInfo.pQueuePriorities = &queuePriority;

    VkPhysicalDeviceFeatures deviceFeatures = {}; // Empty for now

    std::vector<const char*> deviceExtensions = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME
    };

    VkDeviceCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    createInfo.pQueueCreateInfos = &queueCreateInfo;
    createInfo.queueCreateInfoCount = 1;
    createInfo.pEnabledFeatures = &deviceFeatures;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(deviceExtensions.size());
    createInfo.ppEnabledExtensionNames = deviceExtensions.data();

    if (vkCreateDevice(physicalDevice, &createInfo, nullptr, &device) != VK_SUCCESS) {
        LOGE("Failed to create logical device.");
        return false;
    }

    // We would retrieve the queue handle here
    // vkGetDeviceQueue(device, graphicsFamily, 0, &graphicsQueue);

    LOGI("Vulkan Logical Device created.");
    return true;
}

bool VulkanBackend::createSwapchain() { return true; } // Stub
bool VulkanBackend::createRenderPass() { return true; } // Stub
bool VulkanBackend::createPipeline() { return true; } // Stub
bool VulkanBackend::createFramebuffers() { return true; } // Stub
bool VulkanBackend::createCommandPool() { return true; } // Stub
bool VulkanBackend::createCommandBuffers() { return true; } // Stub
bool VulkanBackend::createSyncObjects() { return true; } // Stub

void VulkanBackend::cleanupSwapchain() {
    // Stub: vkDestroySwapchainKHR, etc.
}

void VulkanBackend::recreateSwapchain() {
    cleanupSwapchain();
    createSwapchain();
    createFramebuffers();
}