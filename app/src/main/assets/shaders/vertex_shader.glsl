uniform mat4 u_ModelViewProjection;
uniform mat4 u_ModelView;
attribute vec4 a_Position;
attribute vec2 a_TexCoord;

varying vec2 v_TexCoord;
varying float v_ViewDepth;

void main() {
    gl_Position = u_ModelViewProjection * a_Position;
    v_TexCoord = a_TexCoord;

    // Calculate linear depth (Z-distance from camera)
    // In OpenGL view space, camera looks down -Z, so depth is -Z.
    vec4 viewPos = u_ModelView * a_Position;
    v_ViewDepth = -viewPos.z;
}