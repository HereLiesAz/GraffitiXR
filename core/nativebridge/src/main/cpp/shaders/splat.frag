#version 450

layout(location = 0) in vec4 fragColor;
layout(location = 1) in float fragOpacity;

layout(location = 0) out vec4 outColor;

void main() {
    // Gaussian falloff from center of point sprite
    vec2 coord = gl_PointCoord - vec2(0.5);
    float distSq = dot(coord, coord);
    if (distSq > 0.25) discard;

    float gaussian = exp(-8.0 * distSq);
    outColor = vec4(fragColor.rgb, fragColor.a * gaussian * fragOpacity);
}
