uniform mat4 u_MVPMatrix;
uniform mat3 u_HomographyMatrix;
attribute vec4 a_Position;
attribute vec2 a_TexCoordinate;

varying vec2 v_TexCoordinate;

void main() {
    // Transform texture coordinates by the homography matrix
    vec3 warpedTexCoord = u_HomographyMatrix * vec3(a_TexCoordinate, 1.0);
    // Perform perspective divide
    v_TexCoordinate = warpedTexCoord.xy / warpedTexCoord.z;

    gl_Position = u_MVPMatrix * a_Position;
}