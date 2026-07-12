package com.hereliesaz.graffitixr.common.azphalt

/**
 * A parsed `.cube` 3D colour lookup table — the normalized form azphalt asset extensions ship LUTs
 * as (spec/package-format.md). Applied with trilinear interpolation. Pure/Android-free so the parse
 * and apply are unit-testable; a Bitmap bridge lives alongside in the Android layer.
 *
 * A ColorMatrix (what GraffitiXR's adjustments use) is a 4×5 affine transform and cannot represent a
 * general 3D LUT, so this is its own path.
 */
class CubeLut internal constructor(
    val size: Int,
    /** size³ × 3 floats (r,g,b in [0,1]), with red varying fastest per the .cube spec. */
    private val data: FloatArray,
    private val domainMin: FloatArray,
    private val domainMax: FloatArray,
) {
    init {
        require(size in 2..256) { "LUT_3D_SIZE out of range: $size" }
        require(data.size == size * size * size * 3) { "LUT data size mismatch" }
    }

    private fun indexOf(r: Int, g: Int, b: Int): Int = (r + g * size + b * size * size) * 3

    /** Trilinearly sample the LUT for a normalized colour, writing the graded rgb into [out]. */
    private fun sample(rn: Float, gn: Float, bn: Float, out: FloatArray) {
        val n1 = size - 1
        val rf = (rescale(rn, domainMin[0], domainMax[0]) * n1).coerceIn(0f, n1.toFloat())
        val gf = (rescale(gn, domainMin[1], domainMax[1]) * n1).coerceIn(0f, n1.toFloat())
        val bf = (rescale(bn, domainMin[2], domainMax[2]) * n1).coerceIn(0f, n1.toFloat())

        val r0 = rf.toInt(); val g0 = gf.toInt(); val b0 = bf.toInt()
        val r1 = minOf(r0 + 1, n1); val g1 = minOf(g0 + 1, n1); val b1 = minOf(b0 + 1, n1)
        val dr = rf - r0; val dg = gf - g0; val db = bf - b0

        for (c in 0 until 3) {
            val c000 = data[indexOf(r0, g0, b0) + c]
            val c100 = data[indexOf(r1, g0, b0) + c]
            val c010 = data[indexOf(r0, g1, b0) + c]
            val c110 = data[indexOf(r1, g1, b0) + c]
            val c001 = data[indexOf(r0, g0, b1) + c]
            val c101 = data[indexOf(r1, g0, b1) + c]
            val c011 = data[indexOf(r0, g1, b1) + c]
            val c111 = data[indexOf(r1, g1, b1) + c]
            val x00 = c000 + (c100 - c000) * dr
            val x10 = c010 + (c110 - c010) * dr
            val x01 = c001 + (c101 - c001) * dr
            val x11 = c011 + (c111 - c011) * dr
            val y0 = x00 + (x10 - x00) * dg
            val y1 = x01 + (x11 - x01) * dg
            out[c] = y0 + (y1 - y0) * db
        }
    }

    /**
     * Grade one packed ARGB pixel, preserving alpha. [scratch] is a caller-supplied 3-float work
     * buffer so a hot loop avoids per-pixel allocation; it is never read before it is written, so an
     * uninitialised buffer is fine. Keeping it a parameter (not an instance field) means one [CubeLut]
     * can be applied from multiple threads concurrently without corrupting shared state.
     */
    fun applyPixel(argb: Int, scratch: FloatArray = FloatArray(3)): Int {
        val a = (argb ushr 24) and 0xFF
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        sample(r, g, b, scratch)
        val or = (scratch[0].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val og = (scratch[1].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val ob = (scratch[2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (a shl 24) or (or shl 16) or (og shl 8) or ob
    }

    /** Grade an ARGB pixel array in place. */
    fun applyPixels(pixels: IntArray) {
        val scratch = FloatArray(3)
        for (i in pixels.indices) pixels[i] = applyPixel(pixels[i], scratch)
    }

    private fun rescale(v: Float, min: Float, max: Float): Float =
        if (max > min) ((v - min) / (max - min)) else v
}

/**
 * Parse a `.cube` (Adobe/IRIDAS) 3D LUT. Supports `LUT_3D_SIZE`, `TITLE`, `DOMAIN_MIN`/`DOMAIN_MAX`,
 * comments (`#`), and the size³ triplet rows (red fastest). Throws on malformed input or a 1D LUT
 * (unsupported — asset extensions ship 3D grades).
 */
fun parseCubeLut(text: String): CubeLut {
    var size = -1
    val domainMin = floatArrayOf(0f, 0f, 0f)
    val domainMax = floatArrayOf(1f, 1f, 1f)
    val triples = ArrayList<Float>()

    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val upper = line.uppercase()
        when {
            upper.startsWith("TITLE") -> {}
            upper.startsWith("LUT_1D_SIZE") -> throw IllegalArgumentException("1D .cube LUTs are not supported")
            upper.startsWith("LUT_3D_SIZE") -> {
                val sizeStr = line.substringAfter(' ').trim()
                size = sizeStr.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid LUT_3D_SIZE: $sizeStr")
            }
            upper.startsWith("DOMAIN_MIN") -> readTriplet(line, domainMin)
            upper.startsWith("DOMAIN_MAX") -> readTriplet(line, domainMax)
            else -> {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 3) {
                    triples.add(parts[0].toFloatOrNull()
                        ?: throw IllegalArgumentException("Invalid LUT value: ${parts[0]}"))
                    triples.add(parts[1].toFloatOrNull()
                        ?: throw IllegalArgumentException("Invalid LUT value: ${parts[1]}"))
                    triples.add(parts[2].toFloatOrNull()
                        ?: throw IllegalArgumentException("Invalid LUT value: ${parts[2]}"))
                }
            }
        }
    }
    require(size >= 2) { "Missing or invalid LUT_3D_SIZE" }
    val expected = size * size * size * 3
    require(triples.size == expected) { "Expected $expected LUT values, got ${triples.size}" }
    return CubeLut(size, triples.toFloatArray(), domainMin, domainMax)
}

private fun readTriplet(line: String, out: FloatArray) {
    val parts = line.split(Regex("\\s+"))
    // parts[0] is the keyword; the three numbers follow.
    if (parts.size < 4) throw IllegalArgumentException("Malformed domain line: $line")
    for (i in 0 until 3) {
        out[i] = parts[i + 1].toFloatOrNull()
            ?: throw IllegalArgumentException("Invalid float in domain line: ${parts[i + 1]}")
    }
}
