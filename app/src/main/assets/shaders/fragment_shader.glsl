precision mediump float;
uniform vec4 u_Color;

void main() {
    // MODIFICATION: Hardcoded semi-transparent blue if u_Color isn't set,
    // or relying on the uniform but forcing low alpha.
    // Let's assume you want that "inside joke" blue.
    // R, G, B, Alpha (0.3 is 30% visible)
    gl_FragColor = vec4(0.0, 0.5, 1.0, 0.3);
}