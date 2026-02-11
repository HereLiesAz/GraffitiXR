#version 300 es
precision mediump float;

uniform vec4 u_Color;
in float v_Confidence;
out vec4 FragColor;

void main() {
    // Circular point sprite
    vec2 coord = gl_PointCoord - vec2(0.5);
    if(length(coord) > 0.5) {
        discard;
    }

    // Fade by confidence
    float alpha = u_Color.a * v_Confidence;
    FragColor = vec4(u_Color.rgb, alpha);
}