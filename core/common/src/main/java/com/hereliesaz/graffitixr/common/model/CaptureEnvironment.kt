package com.hereliesaz.graffitixr.common.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * Everything knowable about how and where the device was held at the instant a target was captured.
 *
 * **Why this exists.** Until now the app tracked only the *display* rotation — `display.rotation`
 * plus the camera's fixed `sensorOrientation`, combined into `rotationNeeded`. It had no idea which
 * way the phone was physically pointing, which way was down, or where on earth it was. That is a
 * problem in three separate directions:
 *
 *  - **The capture geometry is frozen at capture.** `PlaneMarks.backProject` runs once per target,
 *    so whatever frame relationship held at that instant is baked into the fingerprint's 3D points
 *    forever (`PAPER.md` §8). If a capture later turns out to be wrong, the only way to attribute it
 *    is to know the conditions it was made under — and those conditions were not recorded.
 *  - **`screenOrientation="fullUser"` respects the auto-rotate setting.** With rotation lock on, the
 *    display rotation is pinned regardless of physical attitude, so `rotationNeeded` can disagree
 *    with how the artist is actually holding the phone. Recording the device attitude alongside it
 *    is what makes that disagreement *visible* rather than merely possible.
 *  - **A mural is a place.** Re-opening a project at the same wall days later is the core workflow,
 *    and a location fix plus a compass heading is the cheapest prior available for "is this the same
 *    wall, seen from roughly the same side".
 *
 * **Everything is optional, and that is the design.** A device may have no magnetometer, location
 * permission may be denied, ARCore may not have a pose yet. Every group is independently nullable so
 * a capture never fails for want of a sensor, and a missing group reads as absent rather than as a
 * plausible zero — the distinction this codebase has already had to relearn twice (the eval CSV's
 * not-sampled sentinel, and a splat counter that returned a hardcoded 0).
 */
@Serializable
@Parcelize
data class CaptureEnvironment(
    /** Wall-clock time of the capture, for correlating with logs and photographs. */
    val capturedAtEpochMs: Long = 0L,
    /**
     * `SystemClock.elapsedRealtimeNanos()` at capture — monotonic, unlike [capturedAtEpochMs].
     *
     * Kept because sensor and location timestamps are on this clock, so it is the only base against
     * which their staleness can be computed. Wall-clock can jump (NTP, time zones, the user); a
     * measured "this fix was 4 s old" cannot be reconstructed from it.
     */
    val elapsedRealtimeNs: Long = 0L,

    /** How the frame was oriented. Never null in practice — these are always computable. */
    val frame: FrameOrientation? = null,
    /** Physical device attitude from the motion sensors. Null when no usable sensor was present. */
    val attitude: DeviceAttitude? = null,
    /** ARCore's own poses at the capture frame. Null when the session had no tracking pose. */
    val arPoses: ArPoseSnapshot? = null,
    /** Last known location fix. Null when unavailable, denied, or never acquired. */
    val location: LocationFix? = null,
) : Parcelable

/**
 * The rotation bookkeeping the capture path already did, now recorded rather than recomputed.
 *
 * `rotationNeeded` is the angle the bitmap and intrinsics were rotated by, and — since Phase 0 — the
 * angle the capture view matrix was rotated by to match. Storing all three inputs rather than just
 * the result means a later reader can check the arithmetic instead of trusting it.
 */
@Serializable
@Parcelize
data class FrameOrientation(
    /** `display.rotation * 90` — 0/90/180/270. What the *window* is doing, not the device. */
    val displayRotationDeg: Int,
    /** `CameraCharacteristics.SENSOR_ORIENTATION`. A fixed hardware constant, usually 90. */
    val sensorOrientationDeg: Int,
    /** `(sensorOrientationDeg - displayRotationDeg + 360) % 360`. */
    val rotationNeededDeg: Int,
    /**
     * Whether the system auto-rotate setting was on.
     *
     * The one field that explains a `rotationNeededDeg` which disagrees with [DeviceAttitude]: with
     * auto-rotate off, `screenOrientation="fullUser"` pins the window and the display rotation stops
     * following the device. Also the precondition for `EVALUATION.md` E0b — with it off, rotating
     * the phone does not change the experiment's independent variable at all.
     */
    val autoRotateEnabled: Boolean = false,
) : Parcelable

/**
 * Physical device attitude, from the motion sensors — the data the app previously never collected.
 *
 * Two rotation vectors are recorded deliberately, because they fail differently. [rotationVector]
 * fuses the magnetometer and so is absolute (its azimuth is true-ish north) but is corrupted by
 * nearby ferrous metal and by the sort of electrical noise a building has plenty of.
 * [gameRotationVector] omits the magnetometer: it cannot give a heading, but it is immune to that
 * corruption. When they disagree, the magnetic environment is bad — which is itself worth knowing at
 * a wall covered in steel reinforcement.
 */
@Serializable
@Parcelize
data class DeviceAttitude(
    /** `TYPE_ROTATION_VECTOR` as a quaternion `[x, y, z, w]`. Empty when unavailable. */
    val rotationVector: List<Float> = emptyList(),
    /** Estimated heading accuracy in radians, or -1 when the sensor does not report one. */
    val rotationVectorAccuracyRad: Float = -1f,
    /** `TYPE_GAME_ROTATION_VECTOR` quaternion `[x, y, z, w]` — magnetometer-free. */
    val gameRotationVector: List<Float> = emptyList(),
    /** `TYPE_GRAVITY` in m/s², device frame. Which way is down, independent of any fusion. */
    val gravity: List<Float> = emptyList(),
    /** `TYPE_MAGNETIC_FIELD` in µT, device frame. */
    val magneticFieldUt: List<Float> = emptyList(),
    /**
     * `SensorManager.SENSOR_STATUS_ACCURACY_*` for the magnetometer, or -1 if unknown.
     *
     * Recorded because `SENSOR_STATUS_UNRELIABLE` is common indoors and near rebar, and it is the
     * difference between "the heading is wrong" and "the heading was never trustworthy".
     */
    val magneticAccuracy: Int = -1,
    /** Azimuth in degrees (0 = magnetic north), or `NaN` when no absolute heading was available. */
    val azimuthDeg: Float = Float.NaN,
    /** Pitch in degrees, or `NaN`. */
    val pitchDeg: Float = Float.NaN,
    /** Roll in degrees, or `NaN`. */
    val rollDeg: Float = Float.NaN,
    /** Age of the newest contributing sensor sample at capture, in ms. -1 when unknown. */
    val sampleAgeMs: Long = -1L,
) : Parcelable

/**
 * ARCore's poses at the capture frame, all three of them, because they are three different frames
 * and this project has already lost a week to conflating two of them.
 *
 * Each is `[tx, ty, tz, qx, qy, qz, qw]`, or empty when unavailable.
 */
@Serializable
@Parcelize
data class ArPoseSnapshot(
    /**
     * `Camera.getPose()` — the **physical** camera, "right"/"up" relative to the image readout.
     * This is the frame the capture view matrix is built from.
     */
    val cameraPose: List<Float> = emptyList(),
    /**
     * `Camera.getDisplayOrientedPose()` — the virtual camera for rendering, relative to the
     * *logical display orientation*. Differs from [cameraPose] by a rotation about Z of a multiple
     * of 90°, which is exactly the `PAPER.md` §8 mismatch.
     */
    val displayOrientedPose: List<Float> = emptyList(),
    /**
     * `Frame.getAndroidSensorPose()` — the Android sensor frame. Differs from both in orientation
     * *and* location, and is the bridge to [DeviceAttitude]: it is the only ARCore pose expressed
     * in the frame the motion sensors report in.
     */
    val androidSensorPose: List<Float> = emptyList(),
) : Parcelable

/**
 * A location fix, with the metadata needed to decide whether to believe it.
 *
 * [ageMs] matters more than the coordinates for this use: a fused provider will happily hand back a
 * fix from twenty minutes and a kilometre ago, and a stale fix recorded without its age is
 * indistinguishable from a fresh one.
 */
@Serializable
@Parcelize
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** Metres above the WGS84 ellipsoid, or `NaN` when the provider gave none. */
    val altitudeM: Double = Double.NaN,
    /** Horizontal 68% confidence radius in metres, or -1 when unreported. */
    val horizontalAccuracyM: Float = -1f,
    /** Vertical accuracy in metres, or -1 when unreported. */
    val verticalAccuracyM: Float = -1f,
    /** Direction of travel in degrees, or `NaN`. Not a compass heading — see [DeviceAttitude]. */
    val bearingDeg: Float = Float.NaN,
    /** Ground speed in m/s, or `NaN`. */
    val speedMps: Float = Float.NaN,
    /** `"gps"`, `"fused"`, `"network"`, … — how the fix was obtained. */
    val provider: String = "",
    /** How old the fix was at capture, in ms. -1 when it could not be computed. */
    val ageMs: Long = -1L,
) : Parcelable
