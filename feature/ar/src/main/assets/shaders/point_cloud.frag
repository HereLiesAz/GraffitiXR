precision mediump float;
varying float v_Confidence;
void main() {
    vec2 coord = gl_PointCoord - vec2(0.5);
    if (length(coord) > 0.5) discard;
    vec3 cyan = vec3(0.0, 1.0, 1.0);
    vec3 pink = vec3(1.0, 0.0, 0.8);
    vec3 green = vec3(0.0, 1.0, 0.0);
    vec3 finalColor;
    if (v_Confidence < 0.5) {
        finalColor = mix(cyan, pink, v_Confidence * 2.0);
    } else {
        finalColor = mix(pink, green, (v_Confidence - 0.5) * 2.0);
    }
    gl_FragColor = vec4(finalColor, 1.0);
}