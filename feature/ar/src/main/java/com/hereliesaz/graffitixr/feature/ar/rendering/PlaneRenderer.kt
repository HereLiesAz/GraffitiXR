package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.common.util.GlReleasable
import com.hereliesaz.graffitixr.design.rendering.ShaderUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class PlaneRenderer : GlReleasable {
    private var planeProgram = 0

    private var planeModelUniform = 0
    private var planeModelViewProjectionUniform = 0
    private var gridControlUniform = 0
    private var planeColorUniform = 0
    private var isOutlineUniform = 0
    private var gridModeUniform = 0
    private var developUniform = 0
    private var fadeUniform = 0
    private var firstDrawNs = -1L // for the ink-spread develop ramp

    /**
     * When each still-drawn plane was first classified as non-green (elapsedRealtime ms). A plane
     * that classifies as MATCH is removed, so going green stops the dissolve outright and turning
     * non-green again restarts the full hold + dissolve from zero.
     *
     * Keyed on [Plane]: ARCore's trackables define equals/hashCode over the underlying native
     * object, so the wrapper instances handed back by successive getAllTrackables calls hash to the
     * same entry. Pruned every pass to whatever is still being drawn, so it can't grow unbounded.
     */
    private val nonGreenSinceMs = HashMap<Plane, Long>()
    private val seenThisPass = HashSet<Plane>()

    /**
     * elapsedRealtime (ms) at which the last in-progress dissolve finishes, or 0 when nothing is
     * dissolving. ArRenderer caches the perception layers in an FBO and only redraws them when the
     * pose moves or the map grows — without this a dissolve would freeze the moment the artist held
     * the phone still, which is exactly when they are looking at it.
     */
    var dissolveCompletesAtMs: Long = 0L
        private set

    private var vertexBuffer = ByteBuffer.allocateDirect(1000 * 4) // Reusable buffer (Float = 4 bytes), grown on demand
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val modelMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)

    fun createOnGlThread(context: Context) {
        val vertexShaderCode = """
            uniform mat4 u_PlaneModel;
            uniform mat4 u_PlaneModelViewProjection;
            attribute vec2 a_PositionXZ;
            varying vec2 v_PosXZ;
            void main() {
                v_PosXZ = a_PositionXZ; // plane-local metres — anchored to the surface
                gl_Position = u_PlaneModelViewProjection * vec4(a_PositionXZ.x, 0.0, a_PositionXZ.y, 1.0);
            }
        """.trimIndent()

        // Ink-develop fill: the colour is "soaked" into the actual surface as a value-noise texture in
        // plane-local space, so it stays put on the wall/floor as the camera moves (unlike the old
        // screen-space reveal). It spreads in as u_Develop rises. Hue still carries the match meaning.
        // u_GridMode = 1 (debug perception view) replaces the ink fill with a metric grid in
        // plane-local metres — 0.25 m cells with the plane's local X/Z axes emphasised — so the
        // plane's ORIENTATION is readable at a glance, not just its silhouette.
        val fragmentShaderCode = """
            precision mediump float;
            uniform vec4 u_Color;
            uniform int u_IsOutline;
            uniform int u_GridMode;
            uniform float u_Develop;
            // 1 = fully present, 0 = gone. Drives the timed dissolve of surfaces that aren't a good
            // match for the artwork, so the scan view declutters down to the usable walls. It erodes
            // through the same value noise the ink fill develops through, so a surface breaks up in
            // patches instead of just dimming uniformly.
            uniform float u_Fade;
            varying vec2 v_PosXZ;
            float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
            float vnoise(vec2 p) {
                vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
                float a = hash(i), b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }
            void main() {
                if (u_IsOutline == 1) {
                    // A line loop is too thin to read as patchy, so the outline just fades out.
                    gl_FragColor = vec4(u_Color.rgb, 0.9 * u_Fade);
                } else if (u_GridMode == 1) {
                    vec2 f = fract(v_PosXZ * 4.0);
                    vec2 d = min(f, 1.0 - f);
                    float line = 1.0 - smoothstep(0.0, 0.018, min(d.x, d.y));
                    float axis = 1.0 - smoothstep(0.0, 0.028, min(abs(v_PosXZ.x), abs(v_PosXZ.y)));
                    // Lines lifted toward white so the grid reads over the camera and any hue.
                    vec3 lineCol = mix(u_Color.rgb, vec3(1.0), 0.55);
                    float a = max(line * 0.85, axis);
                    // Erode the grid through the same plane-local noise, so it breaks up in patches
                    // on the surface as it goes rather than dimming as one flat sheet.
                    float n = vnoise(v_PosXZ * 18.0);
                    a *= smoothstep(n - 0.18, n + 0.18, u_Fade);
                    gl_FragColor = vec4(lineCol, max(a, 0.03) * u_Fade);
                } else {
                    float n = vnoise(v_PosXZ * 18.0);
                    // Running the develop threshold back down is the ink un-soaking from the wall:
                    // the same patches that spread in are the ones that recede.
                    float ink = smoothstep(n - 0.18, n + 0.18, u_Develop * u_Fade);
                    gl_FragColor = vec4(u_Color.rgb, ink * 0.5 * u_Fade);
                }
            }
        """.trimIndent()

        val vertexShader = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val passthroughShader = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)

        planeProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(planeProgram, vertexShader)
        GLES20.glAttachShader(planeProgram, passthroughShader)
        GLES20.glLinkProgram(planeProgram)

        planeModelUniform = GLES20.glGetUniformLocation(planeProgram, "u_PlaneModel")
        planeModelViewProjectionUniform = GLES20.glGetUniformLocation(planeProgram, "u_PlaneModelViewProjection")
        gridControlUniform = GLES20.glGetUniformLocation(planeProgram, "u_gridControl")
        planeColorUniform = GLES20.glGetUniformLocation(planeProgram, "u_Color")
        isOutlineUniform = GLES20.glGetUniformLocation(planeProgram, "u_IsOutline")
        gridModeUniform = GLES20.glGetUniformLocation(planeProgram, "u_GridMode")
        developUniform = GLES20.glGetUniformLocation(planeProgram, "u_Develop")
        fadeUniform = GLES20.glGetUniformLocation(planeProgram, "u_Fade")
    }

    /**
     * @param gridMode When true (debug perception view) planes render as a metric grid with
     * emphasised local axes instead of the ink-develop fill, so their orientation is visible.
     */
    fun drawPlanes(session: Session, viewMatrix: FloatArray, projectionMatrix: FloatArray, cameraPose: Pose, gridMode: Boolean = false) {
        val planes = mergeCoplanar(session.getAllTrackables(Plane::class.java))

        GLES20.glUseProgram(planeProgram)
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUniform1f(gridControlUniform, 1.0f)
        GLES20.glUniform1i(gridModeUniform, if (gridMode) 1 else 0)

        // Ink-spread progress: ramp 0->1 over ~1.5 s from the first drawn frame, so the colour visibly
        // soaks into the surfaces as the scan gets going (then holds).
        if (firstDrawNs < 0L) firstDrawNs = System.nanoTime()
        val develop = ((System.nanoTime() - firstDrawNs) / 1_500_000_000.0f).coerceIn(0f, 1f)
        GLES20.glUniform1f(developUniform, develop)

        val nowMs = android.os.SystemClock.elapsedRealtime()
        var lastDissolveEndMs = 0L
        seenThisPass.clear()

        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
                continue
            }
            seenThisPass.add(plane)

            val match = classifyPlane(plane, cameraPose)
            val fade = fadeFor(plane, match, nowMs)
            if (fade <= 0f) continue // fully dissolved — stays gone until it goes green again
            if (fade < 1f) {
                lastDissolveEndMs = maxOf(lastDissolveEndMs, dissolveEndMs(plane))
            }

            GLES20.glUniform4fv(planeColorUniform, 1, colorFor(match), 0)
            GLES20.glUniform1f(fadeUniform, fade)

            drawPlane(plane, viewMatrix, projectionMatrix)
        }

        // Forget planes that are no longer drawn, so a long session can't accumulate entries for
        // trackables ARCore has since dropped or subsumed.
        nonGreenSinceMs.keys.retainAll(seenThisPass)
        dissolveCompletesAtMs = lastDissolveEndMs

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
    }

    /**
     * Dissolve weight for [plane] this frame: 1 while it is green or still inside the hold, ramping
     * to 0 across [DISSOLVE_MS] once the hold expires.
     *
     * A green (MATCH) classification clears the plane's timer entirely, so a surface that goes green
     * stops dissolving immediately and — if it later stops matching — starts a fresh
     * [HOLD_MS] + [DISSOLVE_MS] rather than resuming where it left off.
     */
    private fun fadeFor(plane: Plane, match: PlaneMatchResult, nowMs: Long): Float {
        if (match == PlaneMatchResult.MATCH) {
            nonGreenSinceMs.remove(plane)
            return 1f
        }
        val since = nonGreenSinceMs.getOrPut(plane) { nowMs }
        return dissolveFade(nowMs - since)
    }

    /** When [plane]'s in-progress dissolve finishes, or 0 if it isn't dissolving. */
    private fun dissolveEndMs(plane: Plane): Long =
        nonGreenSinceMs[plane]?.let { it + HOLD_MS + DISSOLVE_MS } ?: 0L

    /**
     * Collapses co-planar detections down to one plane per real surface.
     *
     * ARCore only sets [Plane.getSubsumedBy] when it actually merges two detections. A single wall
     * scanned from a few angles routinely ends up as several separate TRACKING planes that share a
     * normal and lie on the same infinite plane but are never subsumed — and drawing all of them
     * stacks overlapping, differently-coloured, differently-sized quads on one surface, which is the
     * "surfaces don't combine, they overlap into a mess" the artist sees.
     *
     * Planes are grouped by (normal direction, signed distance along that normal from the origin);
     * within a group only the largest is drawn, so a wall reads as one surface. Groups stay separate
     * when normals differ past [NORMAL_DOT_EPS] or the planes sit on parallel-but-offset surfaces
     * more than [COPLANAR_DIST_M] apart, so genuinely distinct walls are never fused.
     *
     * This is a rendering/selection filter only — no ARCore state is modified, and the discarded
     * planes stay available to hit tests.
     */
    private fun mergeCoplanar(planes: Collection<Plane>): List<Plane> {
        val kept = ArrayList<Plane>(planes.size)
        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val pose = plane.centerPose
            val normal = FloatArray(3)
            pose.getTransformedAxis(1, 1f, normal, 0) // plane local +Y is the surface normal
            var merged = false
            for (i in kept.indices) {
                if (coplanar(kept[i], plane, normal)) {
                    // Keep the bigger detection: it is the one that actually covers the surface, and
                    // the smaller fragments are what produce the overlapping stack.
                    if (area(plane) > area(kept[i])) kept[i] = plane
                    merged = true
                    break
                }
            }
            if (!merged) kept.add(plane)
        }
        return kept
    }

    /** True when [b] (whose world-space normal is [bNormal]) lies on the same infinite plane as [a]. */
    private fun coplanar(a: Plane, b: Plane, bNormal: FloatArray): Boolean {
        val aPose = a.centerPose
        val aNormal = FloatArray(3)
        aPose.getTransformedAxis(1, 1f, aNormal, 0)
        val bPose = b.centerPose
        return isCoplanar(
            aNormal, aPose.tx(), aPose.ty(), aPose.tz(),
            bNormal, bPose.tx(), bPose.ty(), bPose.tz(),
        )
    }

    /** Extent-based area proxy; ARCore reports the plane's extent, not its polygon area. */
    private fun area(plane: Plane): Float = plane.extentX * plane.extentZ

    private fun drawPlane(plane: Plane, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        val polygon = plane.polygon
        if (polygon.remaining() > vertexBuffer.capacity()) {
            // Large/merged plane polygons exceed the initial 500-vertex buffer; grow to fit
            // instead of throwing BufferOverflowException out of the GL frame.
            vertexBuffer = ByteBuffer.allocateDirect(polygon.remaining() * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
        vertexBuffer.clear()
        vertexBuffer.put(polygon)
        vertexBuffer.flip()

        val count = vertexBuffer.limit() / 2

        plane.centerPose.toMatrix(modelMatrix, 0)

        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        GLES20.glUniformMatrix4fv(planeModelUniform, 1, false, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(planeModelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)

        val posAttr = GLES20.glGetAttribLocation(planeProgram, "a_PositionXZ")
        GLES20.glEnableVertexAttribArray(posAttr)
        GLES20.glVertexAttribPointer(posAttr, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        // Draw Fill
        GLES20.glUniform1i(isOutlineUniform, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, count)

        // Draw Outline
        GLES20.glUniform1i(isOutlineUniform, 1)
        GLES20.glLineWidth(5.0f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, count)

        GLES20.glDisableVertexAttribArray(posAttr)
    }

    /**
     * Deletes the plane shader program. The vertex buffer is a plain direct buffer
     * (no GL buffer object) and is reclaimed by GC. Idempotent; must run on the GL thread.
     */
    override fun release() {
        if (planeProgram != 0) { GLES20.glDeleteProgram(planeProgram); planeProgram = 0 }
    }

    enum class PlaneMatchResult {
        MATCH,      // Green: Parallel and close enough
        NO_MATCH,   // Pink: Perpendicular
        SUBOPTIMAL  // Cyan: Parallel but too far/suboptimal angle
    }

    fun classifyPlane(plane: Plane, cameraPose: Pose): PlaneMatchResult {
        val planeNormal = FloatArray(4)
        plane.centerPose.getTransformedAxis(1, 1.0f, planeNormal, 0)

        val cameraForward = FloatArray(4)
        cameraPose.getTransformedAxis(2, -1.0f, cameraForward, 0)

        val dot = planeNormal[0] * cameraForward[0] + planeNormal[1] * cameraForward[1] + planeNormal[2] * cameraForward[2]
        val absDot = abs(dot)

        if (absDot < 0.3f) {
            return PlaneMatchResult.NO_MATCH
        } else if (absDot > 0.65f) {
            val dist = calculateDistance(plane.centerPose, cameraPose)
            return if (dist < 3.0f) {
                PlaneMatchResult.MATCH
            } else {
                PlaneMatchResult.SUBOPTIMAL
            }
        } else {
            return PlaneMatchResult.SUBOPTIMAL
        }
    }

    fun calculatePlaneColor(plane: Plane, cameraPose: Pose): FloatArray =
        colorFor(classifyPlane(plane, cameraPose))

    private fun colorFor(match: PlaneMatchResult): FloatArray = when (match) {
        PlaneMatchResult.MATCH -> floatArrayOf(0.0f, 1.0f, 0.0f, 0.3f)
        PlaneMatchResult.NO_MATCH -> floatArrayOf(1.0f, 0.4f, 0.7f, 0.3f)
        PlaneMatchResult.SUBOPTIMAL -> floatArrayOf(0.0f, 1.0f, 1.0f, 0.3f)
    }

    private fun calculateDistance(p1: Pose, p2: Pose): Float {
        val dx = p1.tx() - p2.tx()
        val dy = p1.ty() - p2.ty()
        val dz = p1.tz() - p2.tz()
        return sqrt(dx*dx + dy*dy + dz*dz)
    }

    companion object {
        private const val TAG = "PlaneRenderer"

        /** How long a surface stays fully drawn after it stops classifying as a green match. */
        const val HOLD_MS = 5_000L

        /** How long the dissolve itself takes once the hold expires. */
        const val DISSOLVE_MS = 10_000L

        /**
         * Dissolve weight from how long a surface has been continuously non-green: 1 through the
         * hold, then a linear ramp to 0 across the dissolve. Split out from the ARCore types so it
         * can be unit-tested. The caller clears the elapsed time whenever the surface goes green, so
         * this only ever sees an unbroken non-green run.
         */
        fun dissolveFade(elapsedSinceNonGreenMs: Long): Float = when {
            elapsedSinceNonGreenMs <= HOLD_MS -> 1f
            elapsedSinceNonGreenMs >= HOLD_MS + DISSOLVE_MS -> 0f
            else -> 1f - (elapsedSinceNonGreenMs - HOLD_MS).toFloat() / DISSOLVE_MS
        }

        /**
         * |dot| of two plane normals above which they count as the same orientation (~14°). Loose
         * enough to absorb the normal jitter between separate detections of one wall, tight enough
         * that a wall and an adjoining floor/ceiling never merge.
         */
        private const val NORMAL_DOT_EPS = 0.97f

        /**
         * Max separation (m) along the shared normal for two same-orientation planes to count as the
         * same surface. Covers ARCore's depth error on a wall without fusing, say, two parallel walls
         * of a corridor.
         */
        private const val COPLANAR_DIST_M = 0.10f

        /**
         * Pure geometry behind [mergeCoplanar]: do two oriented surface patches lie on the same
         * infinite plane? Split out from the ARCore types so it can be unit-tested.
         *
         * @param aNormal unit surface normal of the first patch (world space)
         * @param bNormal unit surface normal of the second patch (world space)
         */
        fun isCoplanar(
            aNormal: FloatArray, aX: Float, aY: Float, aZ: Float,
            bNormal: FloatArray, bX: Float, bY: Float, bZ: Float,
        ): Boolean {
            val dot = aNormal[0] * bNormal[0] + aNormal[1] * bNormal[1] + aNormal[2] * bNormal[2]
            // abs(): one wall seen from opposite sides yields anti-parallel normals.
            if (abs(dot) < NORMAL_DOT_EPS) return false
            val dx = bX - aX
            val dy = bY - aY
            val dz = bZ - aZ
            // Only the offset ALONG the shared normal matters — two patches far apart across the
            // face of the same wall are still the same surface.
            return abs(dx * aNormal[0] + dy * aNormal[1] + dz * aNormal[2]) <= COPLANAR_DIST_M
        }
    }
}
