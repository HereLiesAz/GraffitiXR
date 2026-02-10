#version 300 es
precision mediump float;

in vec3 v_Color;
out vec4 FragColor;

void main() {
    // Convert PointCoord (0..1) to centered (-0.5 .. 0.5)
    vec2 coord = gl_PointCoord - vec2(0.5);

    // Calculate distance squared from center
    float distSq = dot(coord, coord);

    // 1. Circular Culling: Discard corners of the GL_POINT square
    if (distSq > 0.25) discard;

    // 2. Gaussian Falloff (SplaTAM style soft edges)
    // Sigma controls fuzziness.
    float sigma = 0.15;
    float alpha = exp(-distSq / (2.0 * sigma));

    FragColor = vec4(v_Color, alpha);
}