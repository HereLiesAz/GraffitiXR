#version 300 es
precision mediump float;
in vec3 v_WorldPos;
in vec2 v_TexCoord;
uniform float u_gridControl;
uniform vec4 u_Color;
uniform int u_IsOutline;
out vec4 FragColor;
void main() {
    if (u_IsOutline == 1) {
        FragColor = u_Color;
        return;
    }
    float gridSize = 0.5;
    float thickness = 0.02;
    vec3 gridColor = u_Color.rgb;
    vec2 coord = v_WorldPos.xz / gridSize;
    vec2 gridDist = abs(fract(coord - 0.5) - 0.5) / fwidth(coord);
    float line = min(gridDist.x, gridDist.y);
    float gridAlpha = 1.0 - min(line, 1.0);
    float alpha = gridAlpha * u_gridControl;
    float fillAlpha = 0.05 * u_gridControl;
    FragColor = vec4(gridColor, max(alpha, fillAlpha) * u_Color.a);
}