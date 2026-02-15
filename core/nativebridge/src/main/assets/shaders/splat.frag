#version 450

layout(location = 0) in vec4 vColor;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform GlobalUniforms {
    mat4 view;
    mat4 proj;
    float lightIntensity;
    vec3 lightColor;
} ubo;

void main() {
    // Apply lighting: modulate splat color by light intensity and color
    vec3 litColor = vColor.rgb * ubo.lightColor * ubo.lightIntensity;
    outColor = vec4(litColor, vColor.a);
}