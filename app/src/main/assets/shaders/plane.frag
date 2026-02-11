#version 300 es
precision mediump float;

in vec3 v_WorldPos;
in vec2 v_TexCoord;

uniform float u_gridControl; // Visibility/Alpha control
uniform vec4 u_PlaneMat;     // Not strictly used in this procedural version, but kept for compatibility

out vec4 FragColor;

void main() {
    // --- SETTINGS ---
    float gridSize = 0.5; // Grid cell size in meters
    float thickness = 0.02; // Grid line thickness (relative to cell size)
    vec3 gridColor = vec3(1.0, 1.0, 1.0); // White grid

    // --- PROCEDURAL GRID ---
    // 1. Divide world space into cells
    vec2 coord = v_WorldPos.xz / gridSize;

    // 2. Calculate distance to nearest grid line (fract gives 0..1, we want distance to 0 or 1)
    // abs(fract(c - 0.5) - 0.5) gives a triangle wave 0..0.5..0 centered on lines
    vec2 gridDist = abs(fract(coord - 0.5) - 0.5) / fwidth(coord);

    // 3. Determine line intensity based on distance and thickness
    // The 'min' combines x and y lines.
    float line = min(gridDist.x, gridDist.y);

    // 4. Anti-aliased step function
    // 1.0 when on line, 0.0 when off.
    // We simply clamp the distance.
    float gridAlpha = 1.0 - min(line, 1.0);

    // --- OUTPUT ---
    // Base transparency (u_gridControl) + Grid Highlight
    // We want the floor to be transparent (alpha 0) where there is no grid,
    // and semi-transparent where there IS a grid.

    float alpha = gridAlpha * u_gridControl;

    // Optional: Add a very faint fill so the plane is clickable/visible
    float fillAlpha = 0.05 * u_gridControl;

    FragColor = vec4(gridColor, max(alpha, fillAlpha));
}