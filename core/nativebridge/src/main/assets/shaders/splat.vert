#version 450

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec4 aColor;

layout(location = 0) out vec4 vColor;

layout(set = 0, binding = 0) uniform GlobalUniforms {
    mat4 view;
    mat4 proj;
    float lightIntensity;
    vec3 lightColor;
} ubo;

void main() {
    gl_Position = ubo.proj * ubo.view * vec4(aPosition, 1.0);
    gl_PointSize = 10.0;
    vColor = aColor;
}