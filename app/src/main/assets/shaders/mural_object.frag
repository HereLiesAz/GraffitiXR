precision mediump float;

uniform sampler2D u_Texture;
uniform sampler2D u_DepthTexture;

uniform float u_Opacity;
uniform float u_Contrast;
uniform float u_Saturation;

varying vec2 v_TexCoord;
varying vec2 v_DepthTexCoord;
varying vec4 v_ViewPosition;

float InverseLerp(float value, float min_value, float max_value) {
    return clamp((value - min_value) / (max_value - min_value), 0.0, 1.0);
}

float DepthGetMillimeters(in sampler2D depth_texture, in vec2 depth_uv) {
    vec3 packedDepthAndVisibility = texture2D(depth_texture, depth_uv).xyz;
    return dot(packedDepthAndVisibility.xy, vec2(255.0, 256.0 * 255.0));
}

float DepthGetVisibility(in sampler2D depth_texture, in vec2 depth_uv, in float asset_depth_mm) {
    float depth_mm = DepthGetMillimeters(depth_texture, depth_uv);
    const float kDepthTolerancePerMm = 0.015f;
    float visibility_occlusion = clamp(0.5 * (depth_mm - asset_depth_mm) / (kDepthTolerancePerMm * asset_depth_mm) + 0.5, 0.0, 1.0);
    float visibility_depth_near = 1.0 - InverseLerp(depth_mm, 150.0, 200.0);
    float visibility_depth_far = InverseLerp(depth_mm, 7500.0, 8000.0);
    const float kOcclusionAlpha = 0.0f;
    float visibility = max(max(visibility_occlusion, kOcclusionAlpha), max(visibility_depth_near, visibility_depth_far));
    return visibility;
}

void main() {
    vec4 textureColor = texture2D(u_Texture, v_TexCoord);

    // Contrast
    textureColor.rgb = (textureColor.rgb - 0.5) * u_Contrast + 0.5;

    // Saturation
    float luminance = dot(textureColor.rgb, vec3(0.299, 0.587, 0.114));
    vec3 grayscale = vec3(luminance);
    textureColor.rgb = mix(grayscale, textureColor.rgb, u_Saturation);

    // Occlusion
    const float kMetersToMillimeters = 1000.0;
    float asset_depth_mm = v_ViewPosition.z * kMetersToMillimeters * -1.;
    float visibility = DepthGetVisibility(u_DepthTexture, v_DepthTexCoord, asset_depth_mm);
    textureColor.a *= visibility;

    // Opacity
    textureColor.a *= u_Opacity;

    gl_FragColor = textureColor;
}
