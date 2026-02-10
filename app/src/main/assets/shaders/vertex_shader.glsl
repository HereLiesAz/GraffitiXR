#version 300 es

layout(location = 0) in vec4 a_Position; // x,y,z
layout(location = 1) in vec3 a_Color;    // r,g,b
layout(location = 2) in float a_Radius;  // Splat Radius

uniform mat4 u_View;
uniform mat4 u_Proj;

out vec3 v_Color;
out float v_Radius;

void main() {
    vec4 viewPos = u_View * vec4(a_Position.xyz, 1.0);
    gl_Position = u_Proj * viewPos;

    // SplaTAM Visualization:
    // Size attenuates with distance.
    // a_Radius is world size. We project it to screen pixels.
    float dist = length(viewPos.xyz);

    // Simple projection scaling: Size = WorldSize * Scale / Depth
    // 500.0 is an arbitrary scaling factor for the viewport height
    gl_PointSize = (a_Radius * 1000.0) / dist;

    // Clamp to prevent massive splats when too close
    gl_PointSize = clamp(gl_PointSize, 2.0, 64.0);

    v_Color = a_Color;
}