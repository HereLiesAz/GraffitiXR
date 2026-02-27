#version 450

// Per-vertex inputs — matches SplatGaussian struct layout
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec3 inScale;
layout(location = 2) in vec4 inRot;
layout(location = 3) in vec4 inColor;

// Uniform buffer — matches UniformBufferObject in VulkanBackend.h
layout(binding = 0) uniform UniformBufferObject {
    mat4 view;
    mat4 proj;
    float lightIntensity;
    vec3 lightColor;
} ubo;

layout(location = 0) out vec4 fragColor;
layout(location = 1) out float fragOpacity;

void main() {
    vec4 viewPos = ubo.view * vec4(inPos, 1.0);
    gl_Position = ubo.proj * viewPos;

    // Scale point size by splat scale and distance falloff
    float meanScale = (inScale.x + inScale.y + inScale.z) / 3.0;
    float dist = length(viewPos.xyz);
    gl_PointSize = clamp((meanScale * 500.0) / max(dist, 0.1), 1.0, 64.0);

    // Apply light to color: confidence drives alpha
    vec3 litColor = inColor.rgb * ubo.lightIntensity * ubo.lightColor;
    fragColor = vec4(litColor, inColor.a);
    fragOpacity = inColor.a;
}
