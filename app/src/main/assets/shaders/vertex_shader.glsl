uniform mat4 u_ModelViewProjection;
attribute vec4 a_Position;
attribute float a_Confidence; // Assuming you might use this later, keeping it.

void main() {
    gl_Position = u_ModelViewProjection * a_Position;

    // MODIFICATION: Shrink the radius to pinpoints.
    // Was likely 10.0 or 20.0. Now it is 2.0.
    gl_PointSize = 2.0;
}