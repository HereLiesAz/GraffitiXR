#version 300 es
layout(location = 0) in vec3 a_Position;
uniform mat4 u_PlaneModel;
uniform mat4 u_PlaneModelViewProjection;
out vec3 v_WorldPos;
out vec2 v_TexCoord;
void main() {
    gl_Position = u_PlaneModelViewProjection * vec4(a_Position, 1.0);
    vec4 worldPos = u_PlaneModel * vec4(a_Position, 1.0);
    v_WorldPos = worldPos.xyz;
    v_TexCoord = a_Position.xz;
}