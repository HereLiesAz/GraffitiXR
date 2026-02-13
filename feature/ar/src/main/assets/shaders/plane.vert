#version 300 es
layout(location = 0) in vec2 a_PositionXZ;
uniform mat4 u_PlaneModel;
uniform mat4 u_PlaneModelViewProjection;
out vec3 v_WorldPos;
out vec2 v_TexCoord;
void main() {
    vec4 localPos = vec4(a_PositionXZ.x, 0.0, a_PositionXZ.y, 1.0);
    gl_Position = u_PlaneModelViewProjection * localPos;
    vec4 worldPos = u_PlaneModel * localPos;
    v_WorldPos = worldPos.xyz;
    v_TexCoord = a_PositionXZ;
}