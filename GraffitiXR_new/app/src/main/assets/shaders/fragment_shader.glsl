precision mediump float;

uniform sampler2D u_Texture;
uniform float u_Opacity;
uniform float u_Contrast;
uniform float u_Saturation;

varying vec2 v_TexCoordinate;

void main() {
    vec4 textureColor = texture2D(u_Texture, v_TexCoordinate);

    // Apply Saturation
    vec3 grey = vec3(dot(textureColor.rgb, vec3(0.299, 0.587, 0.114)));
    vec3 saturatedColor = mix(grey, textureColor.rgb, u_Saturation);

    // Apply Contrast
    vec3 contrastedColor = mix(vec3(0.5), saturatedColor, u_Contrast);

    // Apply Opacity and set final color
    gl_FragColor = vec4(contrastedColor, textureColor.a * u_Opacity);
}