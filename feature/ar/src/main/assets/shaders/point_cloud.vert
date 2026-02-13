uniform mat4 u_MvpMatrix;
uniform float u_PointSize;
attribute vec4 a_Position;
varying float v_Confidence;
void main() {
    gl_Position = u_MvpMatrix * vec4(a_Position.xyz, 1.0);
    gl_PointSize = u_PointSize;
    v_Confidence = a_Position.w;
}