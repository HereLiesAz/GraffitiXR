package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CaptureEnvironment` goes to disk inside `GraffitiProject`, so the properties that matter are the
 * ones a saved file depends on: every group survives a round trip, every group is independently
 * optional, and a project written before any of this existed still loads.
 *
 * The optionality is not incidental. A device may have no magnetometer, location may be denied,
 * ARCore may have no pose yet — and "absent" has to stay distinguishable from "measured, and
 * happened to be zero". This codebase has had to relearn that twice already: a splat counter that
 * returned a hardcoded `0` and read as a real measurement, and an eval column where `0` would have
 * collided with a genuine control condition.
 */
class CaptureEnvironmentTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun full() = CaptureEnvironment(
        capturedAtEpochMs = 1_770_000_000_000L,
        elapsedRealtimeNs = 987_654_321L,
        frame = FrameOrientation(
            displayRotationDeg = 90,
            sensorOrientationDeg = 90,
            rotationNeededDeg = 0,
            autoRotateEnabled = true,
        ),
        attitude = DeviceAttitude(
            rotationVector = listOf(0.1f, 0.2f, 0.3f, 0.927f),
            rotationVectorAccuracyRad = 0.26f,
            gameRotationVector = listOf(0.11f, 0.21f, 0.31f, 0.92f),
            gravity = listOf(0.05f, -9.79f, 0.4f),
            magneticFieldUt = listOf(21.5f, -3.2f, 41.0f),
            magneticAccuracy = 3,
            azimuthDeg = 143.5f,
            pitchDeg = -12.25f,
            rollDeg = 3.75f,
            sampleAgeMs = 48L,
        ),
        arPoses = ArPoseSnapshot(
            cameraPose = listOf(1f, 2f, 3f, 0f, 0f, 0f, 1f),
            displayOrientedPose = listOf(1f, 2f, 3f, 0f, 0f, 0.707f, 0.707f),
            androidSensorPose = listOf(1.01f, 2.02f, 3.03f, 0f, 0f, 0f, 1f),
        ),
        location = LocationFix(
            latitude = 51.5072,
            longitude = -0.1276,
            altitudeM = 11.0,
            horizontalAccuracyM = 4.5f,
            verticalAccuracyM = 3.0f,
            bearingDeg = 210.5f,
            speedMps = 0.2f,
            provider = "fused",
            ageMs = 1_200L,
        ),
    )

    @Test
    fun `a full record survives a serialization round trip`() {
        val decoded = json.decodeFromString<CaptureEnvironment>(json.encodeToString(full()))
        assertEquals(full(), decoded)
    }

    /**
     * The realistic device: no magnetometer, location denied, ARCore not yet tracking. A capture
     * must still produce a usable record rather than failing or inventing values.
     */
    @Test
    fun `a record with only frame orientation round trips`() {
        val sparse = CaptureEnvironment(
            capturedAtEpochMs = 1L,
            frame = FrameOrientation(0, 90, 90),
        )
        val decoded = json.decodeFromString<CaptureEnvironment>(json.encodeToString(sparse))
        assertEquals(sparse, decoded)
        assertNull(decoded.attitude)
        assertNull(decoded.arPoses)
        assertNull(decoded.location)
    }

    /** Every group defaults to absent, so a capture never fails for want of a sensor. */
    @Test
    fun `an empty record is constructible and wholly absent`() {
        val e = CaptureEnvironment()
        assertNull(e.frame); assertNull(e.attitude); assertNull(e.arPoses); assertNull(e.location)
    }

    /**
     * A project saved before any of this existed must still deserialize. `captureEnvironment` is
     * nullable with a default for exactly this reason — the alternative is that every pre-existing
     * project fails to load.
     */
    @Test
    fun `a payload with no capture environment decodes to null`() {
        val decoded = json.decodeFromString<Holder>("""{"name":"legacy"}""")
        assertEquals("legacy", decoded.name)
        assertNull(decoded.captureEnvironment)
    }

    @kotlinx.serialization.Serializable
    data class Holder(val name: String, val captureEnvironment: CaptureEnvironment? = null)

    /**
     * Sentinels, pinned. Each says "not measured" and each is outside the range of a real reading:
     * a magnetometer accuracy is 0..3, an age is never negative, and NaN is not a bearing.
     */
    @Test
    fun `unmeasured values use sentinels that cannot be mistaken for readings`() {
        val a = DeviceAttitude()
        assertTrue("azimuth", a.azimuthDeg.isNaN())
        assertTrue("pitch", a.pitchDeg.isNaN())
        assertTrue("roll", a.rollDeg.isNaN())
        assertEquals("magnetic accuracy is 0..3 when real", -1, a.magneticAccuracy)
        assertEquals(-1f, a.rotationVectorAccuracyRad, 0f)
        assertEquals(-1L, a.sampleAgeMs)
        assertTrue("no vector is an empty list, not a zero vector", a.rotationVector.isEmpty())
        assertTrue(a.gravity.isEmpty())

        val l = LocationFix(latitude = 0.0, longitude = 0.0)
        assertTrue("altitude", l.altitudeM.isNaN())
        assertEquals(-1f, l.horizontalAccuracyM, 0f)
        assertEquals(-1L, l.ageMs)
    }

    /**
     * `(0, 0)` is a real coordinate — Null Island — so a LocationFix must never be inferred from
     * its values. Absence is expressed by the whole object being null, which is why
     * [CaptureEnvironment.location] is nullable rather than a fix with sentinel coordinates.
     */
    @Test
    fun `a zero coordinate is a location, not an absent one`() {
        val e = CaptureEnvironment(location = LocationFix(latitude = 0.0, longitude = 0.0))
        assertEquals(0.0, e.location!!.latitude, 0.0)
        assertNull("absence is the null object", CaptureEnvironment().location)
    }

    /**
     * The field that explains a `rotationNeededDeg` disagreeing with the physical attitude, and
     * E0b's precondition. It defaults to false — the conservative reading, since a record that
     * predates the field cannot claim auto-rotate was on.
     */
    @Test
    fun `auto-rotate defaults to false rather than assuming it was on`() {
        assertEquals(false, FrameOrientation(0, 90, 90).autoRotateEnabled)
    }

    /** The arithmetic the capture path performs, pinned so a stored record can be re-checked. */
    @Test
    fun `rotationNeeded is consistent with its two inputs`() {
        for (display in intArrayOf(0, 90, 180, 270)) {
            val f = FrameOrientation(
                displayRotationDeg = display,
                sensorOrientationDeg = 90,
                rotationNeededDeg = (90 - display + 360) % 360,
            )
            assertEquals(
                "display=$display",
                (f.sensorOrientationDeg - f.displayRotationDeg + 360) % 360,
                f.rotationNeededDeg,
            )
        }
    }
}
