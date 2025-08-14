precision mediump float;

uniform sampler2D u_Texture;
uniform float u_Opacity;
uniform float u_Contrast;
uniform float u_Saturation;

varying vec2 v_TexCoord;

void main() {
    vec4 textureColor = texture2D(u_Texture, v_TexCoord);

    // Contrast
    textureColor.rgb = (textureColor.rgb - 0.5) * u_Contrast + 0.5;

    // Saturation
    float luminance = dot(textureColor.rgb, vec3(0.299, 0.587, 0.114));
    vec3 grayscale = vec3(luminance);
    textureColor.rgb = mix(grayscale, textureColor.rgb, u_Saturation);

    // Opacity
    textureColor.a *= u_Opacity;

    gl_FragColor = textureColor;
}
