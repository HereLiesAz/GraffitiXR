#version 300 es

layout(location = 0) in vec4 a_Position; // x, y, z, confidence

uniform mat4 u_ModelViewProjection;
uniform float u_PointSize;

out float v_Confidence;

void main() {
    gl_Position = u_ModelViewProjection * vec4(a_Position.xyz, 1.0);
    gl_PointSize = u_PointSize;
    v_Confidence = a_Position.w;
}