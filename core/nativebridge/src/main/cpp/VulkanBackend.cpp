// ~~~ FILE: ./core/nativebridge/src/main/cpp/VulkanBackend.cpp ~~~
#include "include/VulkanBackend.h"
#include "include/MobileGS.h"
#include <android/log.h>
#include <stdexcept>
#include <vector>
#include <cstring>
#include <algorithm>

#define LOG_TAG "VulkanBackend"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

const int MAX_FRAMES_IN_FLIGHT = 2;

VulkanBackend::VulkanBackend() {}

VulkanBackend::~VulkanBackend() {
    destroy();
}

bool VulkanBackend::initialize(ANativeWindow* window, AAssetManager* assetManager) {
    m_window = window;
    m_assetManager = assetManager;

    if (!createInstance()) return false;
    if (!createSurface()) return false;
    if (!pickPhysicalDevice()) return false;
    if (!createLogicalDevice()) return false;
    if (!createSwapchain()) return false;
    if (!createImageViews()) return false;
    if (!createRenderPass()) return false;
    if (!createGraphicsPipeline()) return false;
    if (!createFramebuffers()) return false;
    if (!createCommandPool()) return false;

    createBuffer(sizeof(UniformBufferObject), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                 VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                 m_uniformBuffer, m_uniformBufferMemory);

    if (!createCommandBuffers()) return false;
    if (!createSyncObjects()) return false;

    LOGI("Vulkan Backend Initialized Successfully");
    return true;
}

void VulkanBackend::destroy() {
    if (m_device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(m_device);
        cleanupSwapchain();

        vkDestroyBuffer(m_device, m_uniformBuffer, nullptr);
        vkFreeMemory(m_device, m_uniformBufferMemory, nullptr);

        vkDestroyBuffer(m_device, m_vertexBuffer, nullptr);
        vkFreeMemory(m_device, m_vertexBufferMemory, nullptr);

        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            vkDestroySemaphore(m_device, m_imageAvailableSemaphores[i], nullptr);
            vkDestroySemaphore(m_device, m_renderFinishedSemaphores[i], nullptr);
            vkDestroyFence(m_device, m_inFlightFences[i], nullptr);
        }

        vkDestroyCommandPool(m_device, m_commandPool, nullptr);
        vkDestroyPipelineLayout(m_device, m_pipelineLayout, nullptr);
        vkDestroyRenderPass(m_device, m_renderPass, nullptr);
        vkDestroyDevice(m_device, nullptr);
    }

    if (m_instance != VK_NULL_HANDLE) {
        if (m_surface != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(m_instance, m_surface, nullptr);
            m_surface = VK_NULL_HANDLE;
        }
        vkDestroyInstance(m_instance, nullptr);
        m_instance = VK_NULL_HANDLE;
    }
}

bool VulkanBackend::createInstance() {
    VkApplicationInfo appInfo{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    appInfo.pApplicationName = "GraffitiXR";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "MobileGS";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;
    std::vector<const char*> extensions = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo createInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    createInfo.ppEnabledExtensionNames = extensions.data();
    return vkCreateInstance(&createInfo, nullptr, &m_instance) == VK_SUCCESS;
}

bool VulkanBackend::createSurface() {
    VkAndroidSurfaceCreateInfoKHR createInfo{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    createInfo.window = m_window;
    return vkCreateAndroidSurfaceKHR(m_instance, &createInfo, nullptr, &m_surface) == VK_SUCCESS;
}

bool VulkanBackend::pickPhysicalDevice() {
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(m_instance, &deviceCount, nullptr);
    if (deviceCount == 0) return false;
    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(m_instance, &deviceCount, devices.data());
    m_physicalDevice = devices[0];
    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(m_physicalDevice, &queueFamilyCount, nullptr);
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(m_physicalDevice, &queueFamilyCount, queueFamilies.data());
    for (uint32_t i = 0; i < queueFamilyCount; i++) {
        if (queueFamilies[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
            VkBool32 presentSupport = false;
            vkGetPhysicalDeviceSurfaceSupportKHR(m_physicalDevice, i, m_surface, &presentSupport);
            if (presentSupport) {
                m_graphicsQueueFamilyIndex = i;
                return true;
            }
        }
    }
    return false;
}

bool VulkanBackend::createLogicalDevice() {
    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueCreateInfo.queueFamilyIndex = m_graphicsQueueFamilyIndex;
    queueCreateInfo.queueCount = 1;
    queueCreateInfo.pQueuePriorities = &queuePriority;
    std::vector<const char*> deviceExtensions = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo createInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    createInfo.pQueueCreateInfos = &queueCreateInfo;
    createInfo.queueCreateInfoCount = 1;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(deviceExtensions.size());
    createInfo.ppEnabledExtensionNames = deviceExtensions.data();
    if (vkCreateDevice(m_physicalDevice, &createInfo, nullptr, &m_device) != VK_SUCCESS) return false;
    vkGetDeviceQueue(m_device, m_graphicsQueueFamilyIndex, 0, &m_graphicsQueue);
    return true;
}

bool VulkanBackend::createSwapchain() {
    VkSurfaceCapabilitiesKHR capabilities;
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(m_physicalDevice, m_surface, &capabilities);
    uint32_t imageCount = std::min(capabilities.minImageCount + 1, capabilities.maxImageCount > 0 ? capabilities.maxImageCount : capabilities.minImageCount + 1);
    m_swapchainExtent = capabilities.currentExtent;
    m_swapchainFormat = VK_FORMAT_R8G8B8A8_UNORM;
    VkSwapchainCreateInfoKHR createInfo{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    createInfo.surface = m_surface;
    createInfo.minImageCount = imageCount;
    createInfo.imageFormat = m_swapchainFormat;
    createInfo.imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    createInfo.imageExtent = m_swapchainExtent;
    createInfo.imageArrayLayers = 1;
    createInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    createInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    createInfo.preTransform = capabilities.currentTransform;
    createInfo.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    createInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    createInfo.clipped = VK_TRUE;
    if (vkCreateSwapchainKHR(m_device, &createInfo, nullptr, &m_swapchain) != VK_SUCCESS) return false;
    vkGetSwapchainImagesKHR(m_device, m_swapchain, &imageCount, nullptr);
    m_swapchainImages.resize(imageCount);
    return vkGetSwapchainImagesKHR(m_device, m_swapchain, &imageCount, m_swapchainImages.data()) == VK_SUCCESS;
}

bool VulkanBackend::createImageViews() {
    m_swapchainImageViews.resize(m_swapchainImages.size());
    for (size_t i = 0; i < m_swapchainImages.size(); i++) {
        VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        viewInfo.image = m_swapchainImages[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = m_swapchainFormat;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.layerCount = 1;
        viewInfo.subresourceRange.levelCount = 1;
        if (vkCreateImageView(m_device, &viewInfo, nullptr, &m_swapchainImageViews[i]) != VK_SUCCESS) return false;
    }
    return true;
}

bool VulkanBackend::createRenderPass() {
    VkAttachmentDescription colorAttachment{};
    colorAttachment.format = m_swapchainFormat;
    colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
    colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    colorAttachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference colorAttachmentRef{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorAttachmentRef;
    VkRenderPassCreateInfo renderPassInfo{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    renderPassInfo.attachmentCount = 1;
    renderPassInfo.pAttachments = &colorAttachment;
    renderPassInfo.subpassCount = 1;
    renderPassInfo.pSubpasses = &subpass;
    return vkCreateRenderPass(m_device, &renderPassInfo, nullptr, &m_renderPass) == VK_SUCCESS;
}

bool VulkanBackend::createGraphicsPipeline() {
    auto vertShaderCode = readFile("shaders/splat.vert.spv");
    auto fragShaderCode = readFile("shaders/splat.frag.spv");
    if (vertShaderCode.empty() || fragShaderCode.empty()) return false;
    VkShaderModule vertModule = createShaderModule(vertShaderCode);
    VkShaderModule fragModule = createShaderModule(fragShaderCode);

    VkPipelineShaderStageCreateInfo vertStageInfo{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_VERTEX_BIT, vertModule, "main"};
    VkPipelineShaderStageCreateInfo fragStageInfo{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_FRAGMENT_BIT, fragModule, "main"};
    VkPipelineShaderStageCreateInfo stages[] = {vertStageInfo, fragStageInfo};

    VkVertexInputBindingDescription bindingDesc{0, sizeof(SplatGaussian), VK_VERTEX_INPUT_RATE_VERTEX};
    VkVertexInputAttributeDescription attrDescs[4];
    attrDescs[0] = {0, 0, VK_FORMAT_R32G32B32_SFLOAT, offsetof(SplatGaussian, pos)};
    attrDescs[1] = {1, 0, VK_FORMAT_R32G32B32_SFLOAT, offsetof(SplatGaussian, scale)};
    attrDescs[2] = {2, 0, VK_FORMAT_R32G32B32A32_SFLOAT, offsetof(SplatGaussian, rot)};
    attrDescs[3] = {3, 0, VK_FORMAT_R32G32B32A32_SFLOAT, offsetof(SplatGaussian, color)};
    VkPipelineVertexInputStateCreateInfo vertexInputInfo{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO, nullptr, 0, 1, &bindingDesc, 4, attrDescs};
    VkPipelineInputAssemblyStateCreateInfo inputAssembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, nullptr, 0, VK_PRIMITIVE_TOPOLOGY_POINT_LIST, VK_FALSE};
    VkViewport viewport{0.0f, 0.0f, (float)m_swapchainExtent.width, (float)m_swapchainExtent.height, 0.0f, 1.0f};
    VkRect2D scissor{{0, 0}, m_swapchainExtent};
    VkPipelineViewportStateCreateInfo viewportState{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, nullptr, 0, 1, &viewport, 1, &scissor};
    VkPipelineRasterizationStateCreateInfo rasterizer{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO, nullptr, 0, VK_FALSE, VK_FALSE, VK_POLYGON_MODE_FILL, static_cast<VkCullModeFlags>(VK_CULL_MODE_NONE), VK_FRONT_FACE_CLOCKWISE, VK_FALSE, 0.0f, 0.0f, 0.0f, 1.0f};
    VkPipelineMultisampleStateCreateInfo multisampling{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO, nullptr, 0, VK_SAMPLE_COUNT_1_BIT, VK_FALSE};
    VkPipelineColorBlendAttachmentState blendAttachment{VK_TRUE, VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_OP_ADD, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD, 0xF};
    VkPipelineColorBlendStateCreateInfo colorBlending{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO, nullptr, 0, VK_FALSE, VK_LOGIC_OP_COPY, 1, &blendAttachment};
    VkPipelineLayoutCreateInfo pipelineLayoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    if (vkCreatePipelineLayout(m_device, &pipelineLayoutInfo, nullptr, &m_pipelineLayout) != VK_SUCCESS) return false;

    VkGraphicsPipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, nullptr, 0, 2, stages, &vertexInputInfo, &inputAssembly, nullptr, &viewportState, &rasterizer, &multisampling, nullptr, &colorBlending, nullptr, m_pipelineLayout, m_renderPass, 0};
    bool success = vkCreateGraphicsPipelines(m_device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &m_graphicsPipeline) == VK_SUCCESS;
    vkDestroyShaderModule(m_device, fragModule, nullptr);
    vkDestroyShaderModule(m_device, vertModule, nullptr);
    return success;
}

bool VulkanBackend::createFramebuffers() {
    m_swapchainFramebuffers.resize(m_swapchainImageViews.size());
    for (size_t i = 0; i < m_swapchainImageViews.size(); i++) {
        VkImageView attachments[] = {m_swapchainImageViews[i]};
        VkFramebufferCreateInfo framebufferInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, nullptr, 0, m_renderPass, 1, attachments, m_swapchainExtent.width, m_swapchainExtent.height, 1};
        if (vkCreateFramebuffer(m_device, &framebufferInfo, nullptr, &m_swapchainFramebuffers[i]) != VK_SUCCESS) return false;
    }
    return true;
}

bool VulkanBackend::createCommandPool() {
    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, nullptr, VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, m_graphicsQueueFamilyIndex};
    return vkCreateCommandPool(m_device, &poolInfo, nullptr, &m_commandPool) == VK_SUCCESS;
}

bool VulkanBackend::createCommandBuffers() {
    m_commandBuffers.resize(MAX_FRAMES_IN_FLIGHT);
    VkCommandBufferAllocateInfo allocInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, nullptr, m_commandPool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, (uint32_t)m_commandBuffers.size()};
    return vkAllocateCommandBuffers(m_device, &allocInfo, m_commandBuffers.data()) == VK_SUCCESS;
}

void VulkanBackend::renderFrame(const std::vector<SplatGaussian>& splats) {
    if (m_device == VK_NULL_HANDLE || m_swapchain == VK_NULL_HANDLE) return;
    vkWaitForFences(m_device, 1, &m_inFlightFences[m_currentFrame], VK_TRUE, UINT64_MAX);
    uploadSplatData(splats);
    uint32_t imageIndex;
    VkResult result = vkAcquireNextImageKHR(m_device, m_swapchain, UINT64_MAX, m_imageAvailableSemaphores[m_currentFrame], VK_NULL_HANDLE, &imageIndex);
    if (result == VK_ERROR_OUT_OF_DATE_KHR) { recreateSwapchain(); return; }
    updateUniformBuffer();
    vkResetFences(m_device, 1, &m_inFlightFences[m_currentFrame]);
    vkResetCommandBuffer(m_commandBuffers[m_currentFrame], 0);

    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    vkBeginCommandBuffer(m_commandBuffers[m_currentFrame], &beginInfo);
    VkRenderPassBeginInfo renderPassInfo{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    renderPassInfo.renderPass = m_renderPass;
    renderPassInfo.framebuffer = m_swapchainFramebuffers[imageIndex];
    renderPassInfo.renderArea.extent = m_swapchainExtent;
    VkClearValue clearColor = {{{0.0f, 0.0f, 0.0f, 0.0f}}};
    renderPassInfo.clearValueCount = 1;
    renderPassInfo.pClearValues = &clearColor;
    vkCmdBeginRenderPass(m_commandBuffers[m_currentFrame], &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(m_commandBuffers[m_currentFrame], VK_PIPELINE_BIND_POINT_GRAPHICS, m_graphicsPipeline);
    if (m_vertexBuffer != VK_NULL_HANDLE && !splats.empty()) {
        VkBuffer vertexBuffers[] = {m_vertexBuffer};
        VkDeviceSize offsets[] = {0};
        vkCmdBindVertexBuffers(m_commandBuffers[m_currentFrame], 0, 1, vertexBuffers, offsets);
        vkCmdDraw(m_commandBuffers[m_currentFrame], static_cast<uint32_t>(splats.size()), 1, 0, 0);
    }
    vkCmdEndRenderPass(m_commandBuffers[m_currentFrame]);
    vkEndCommandBuffer(m_commandBuffers[m_currentFrame]);

    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    VkSemaphore waitSemaphores[] = {m_imageAvailableSemaphores[m_currentFrame]};
    VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    submitInfo.waitSemaphoreCount = 1;
    submitInfo.pWaitSemaphores = waitSemaphores;
    submitInfo.pWaitDstStageMask = waitStages;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &m_commandBuffers[m_currentFrame];
    VkSemaphore signalSemaphores[] = {m_renderFinishedSemaphores[m_currentFrame]};
    submitInfo.signalSemaphoreCount = 1;
    submitInfo.pSignalSemaphores = signalSemaphores;
    if (vkQueueSubmit(m_graphicsQueue, 1, &submitInfo, m_inFlightFences[m_currentFrame]) != VK_SUCCESS) return;

    VkPresentInfoKHR presentInfo{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    presentInfo.waitSemaphoreCount = 1;
    presentInfo.pWaitSemaphores = signalSemaphores;
    presentInfo.swapchainCount = 1;
    presentInfo.pSwapchains = &m_swapchain;
    presentInfo.pImageIndices = &imageIndex;
    result = vkQueuePresentKHR(m_graphicsQueue, &presentInfo);
    if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
        recreateSwapchain();
    }
    m_currentFrame = (m_currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
}

void VulkanBackend::resize(int width, int height) {
    if(width > 0 && height > 0) {
        recreateSwapchain();
    }
}

void VulkanBackend::updateCamera(const float* viewMatrix, const float* projectionMatrix) {
    memcpy(m_ubo.view, viewMatrix, 16 * sizeof(float));
    memcpy(m_ubo.proj, projectionMatrix, 16 * sizeof(float));
}

void VulkanBackend::setLighting(float intensity, const float* colorCorrection) {
    m_ubo.lightIntensity = intensity;
    memcpy(m_ubo.lightColor, colorCorrection, 3 * sizeof(float));
}

uint32_t VulkanBackend::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(m_physicalDevice, &memProperties);
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    throw std::runtime_error("failed to find suitable memory type!");
}

bool VulkanBackend::createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags properties, VkBuffer& buffer, VkDeviceMemory& bufferMemory) {
    VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.size = size;
    bufferInfo.usage = usage;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(m_device, &bufferInfo, nullptr, &buffer) != VK_SUCCESS) return false;
    VkMemoryRequirements memRequirements;
    vkGetBufferMemoryRequirements(m_device, buffer, &memRequirements);
    VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = findMemoryType(memRequirements.memoryTypeBits, properties);
    if (vkAllocateMemory(m_device, &allocInfo, nullptr, &bufferMemory) != VK_SUCCESS) return false;
    vkBindBufferMemory(m_device, buffer, bufferMemory, 0);
    return true;
}

void VulkanBackend::uploadSplatData(const std::vector<SplatGaussian>& splats) {
    if (splats.empty()) {
        if (m_vertexBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(m_device, m_vertexBuffer, nullptr);
            vkFreeMemory(m_device, m_vertexBufferMemory, nullptr);
            m_vertexBuffer = VK_NULL_HANDLE;
            m_vertexBufferSize = 0;
        }
        return;
    }
    VkDeviceSize bufferSize = sizeof(SplatGaussian) * splats.size();
    if (bufferSize > m_vertexBufferSize) {
        if (m_vertexBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(m_device, m_vertexBuffer, nullptr);
            vkFreeMemory(m_device, m_vertexBufferMemory, nullptr);
        }
        m_vertexBufferSize = bufferSize * 1.5; // Allocate extra to prevent frequent reallocations
        createBuffer(m_vertexBufferSize, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, m_vertexBuffer, m_vertexBufferMemory);
    }
    void* data;
    vkMapMemory(m_device, m_vertexBufferMemory, 0, bufferSize, 0, &data);
    memcpy(data, splats.data(), static_cast<size_t>(bufferSize));
    vkUnmapMemory(m_device, m_vertexBufferMemory);
}

void VulkanBackend::updateUniformBuffer() {
    void* data;
    vkMapMemory(m_device, m_uniformBufferMemory, 0, sizeof(m_ubo), 0, &data);
    memcpy(data, &m_ubo, sizeof(m_ubo));
    vkUnmapMemory(m_device, m_uniformBufferMemory);
}

std::vector<char> VulkanBackend::readFile(const std::string& filename) {
    if (!m_assetManager) return {};
    AAsset* file = AAssetManager_open(m_assetManager, filename.c_str(), AASSET_MODE_BUFFER);
    if (!file) {
        LOGE("Failed to open shader file: %s", filename.c_str());
        return {};
    }
    size_t fileSize = AAsset_getLength(file);
    std::vector<char> buffer(fileSize);
    AAsset_read(file, buffer.data(), fileSize);
    AAsset_close(file);
    return buffer;
}

VkShaderModule VulkanBackend::createShaderModule(const std::vector<char>& code) {
    VkShaderModuleCreateInfo createInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    createInfo.codeSize = code.size();
    createInfo.pCode = reinterpret_cast<const uint32_t*>(code.data());
    VkShaderModule shaderModule;
    if (vkCreateShaderModule(m_device, &createInfo, nullptr, &shaderModule) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }
    return shaderModule;
}