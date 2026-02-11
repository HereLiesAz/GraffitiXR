#version 300 es

layout(location = 0) in vec3 a_Position; // Local Plane Coords (x, 0, z)

uniform mat4 u_PlaneModel; // Local -> World
uniform mat4 u_PlaneModelViewProjection; // Local -> Clip

out vec3 v_WorldPos;
out vec2 v_TexCoord;

void main() {
    // 1. Calculate Clip Space Position for rendering
    gl_Position = u_PlaneModelViewProjection * vec4(a_Position, 1.0);

    // 2. Calculate World Space Position for the grid pattern
    // We want the grid to align with the world, not the specific plane mesh center
    vec4 worldPos = u_PlaneModel * vec4(a_Position, 1.0);
    v_WorldPos = worldPos.xyz;

    // 3. Pass through UVs (scaled for texture if needed, but we use world pos for grid)
    v_TexCoord = a_Position.xz;
}