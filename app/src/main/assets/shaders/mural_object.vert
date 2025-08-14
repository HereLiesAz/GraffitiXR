precision mediump float;

attribute vec4 a_Position;
attribute vec2 a_TexCoord;

uniform mat4 u_Projection;
uniform mat4 u_View;
uniform mat4 u_Model;

varying vec2 v_TexCoord;

void main() {
    gl_Position = u_Projection * u_View * u_Model * a_Position;
    v_TexCoord = a_TexCoord;
}
