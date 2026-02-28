#version 450

layout(location = 0) in vec4 fragColor;
layout(location = 1) in float fragOpacity;

layout(location = 0) out vec4 outColor;

// Overlay texture (set=0, binding=1); binding=0 is the UBO in the vertex shader.
layout(set = 0, binding = 1) uniform sampler2D overlayTex;

layout(push_constant) uniform PushConstants {
    int   visualizationMode; // 0=RGB, 1=Heatmap
    int   overlayEnabled;    // 0=no overlay, 1=alpha-blend overlay over splat
    float viewportWidth;
    float viewportHeight;
} pc;

vec3 heatmap(float t) {
    t = clamp(t, 0.0, 1.0);
    return vec3(
        smoothstep(0.5, 1.0, t),
        1.0 - abs(t - 0.5) * 2.0,
        smoothstep(0.5, 0.0, t)
    );
}

void main() {
    vec2 coord = gl_PointCoord - vec2(0.5);
    float distSq = dot(coord, coord);
    if (distSq > 0.25) discard;

    float gaussian = exp(-8.0 * distSq);
    vec3 rgb = (pc.visualizationMode == 1) ? heatmap(fragOpacity) : fragColor.rgb;

    if (pc.overlayEnabled == 1) {
        vec2 uv = gl_FragCoord.xy / vec2(pc.viewportWidth, pc.viewportHeight);
        vec4 overlay = texture(overlayTex, uv);
        rgb = mix(rgb, overlay.rgb, overlay.a);
    }

    outColor = vec4(rgb, fragColor.a * gaussian * fragOpacity);
}
