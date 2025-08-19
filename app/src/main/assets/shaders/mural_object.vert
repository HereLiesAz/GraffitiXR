#version 300 es
precision mediump float;

layout(location = 0) in vec4 a_Position;
layout(location = 1) in vec2 a_TexCoord;
layout(location = 2) in vec2 a_DepthTexCoord;

uniform mat4 u_Projection;
uniform mat4 u_View;
uniform mat4 u_Model;

out vec2 v_TexCoord;
out vec2 v_DepthTexCoord;
out vec4 v_ViewPosition;

void main() {
    v_ViewPosition = u_View * u_Model * a_Position;
    gl_Position = u_Projection * v_ViewPosition;
    v_TexCoord = a_TexCoord;
    v_DepthTexCoord = a_DepthTexCoord;
}
