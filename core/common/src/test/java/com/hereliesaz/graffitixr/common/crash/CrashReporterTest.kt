package com.hereliesaz.graffitixr.common.crash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {

    /** Builds a throwable with a [android.hardware.camera2.CameraManager.getCameraCharacteristics] frame. */
    private fun withGetCharacteristicsFrame(t: Throwable): Throwable = t.apply {
        stackTrace = arrayOf(
            StackTraceElement(
                "android.hardware.camera2.CameraManager",
                "getCameraCharacteristics",
                "CameraManager.java",
                776,
            ),
        )
    }

    @Test
    fun `recognises the real ARCore camera-pipe teardown crash`() {
        // Mirrors SM-A236U crash: ISE "Failed to load metadata" <- IAE getCameraCharacteristics.
        val cause = withGetCharacteristicsFrame(
            IllegalArgumentException(
                "getCameraCharacteristics:1041: Unable to retrieve camera characteristics for " +
                    "unknown device 0: No such file or directory (-2)",
            ),
        )
        val crash = IllegalStateException("Failed to load metadata for CameraId-0!", cause)

        assertTrue(CrashReporter.isRecoverableArCameraCrash(crash))
    }

    @Test
    fun `recognises the signature when carried only by a suppressed throwable`() {
        val suppressed = withGetCharacteristicsFrame(
            IllegalStateException("Failed to load metadata for CameraId-1!"),
        )
        val crash = RuntimeException("camera pipe shutdown").apply { addSuppressed(suppressed) }

        assertTrue(CrashReporter.isRecoverableArCameraCrash(crash))
    }

    @Test
    fun `does not match when only the message signature is present`() {
        // Same message, but no getCameraCharacteristics frame -> not the pipe teardown, don't swallow.
        val crash = IllegalStateException("Failed to load metadata for CameraId-0!")

        assertFalse(CrashReporter.isRecoverableArCameraCrash(crash))
    }

    @Test
    fun `does not match when only the camera frame is present`() {
        val crash = withGetCharacteristicsFrame(RuntimeException("some unrelated camera query"))

        assertFalse(CrashReporter.isRecoverableArCameraCrash(crash))
    }

    @Test
    fun `recognises CameraX SecurityException eviction crash`() {
        // When ARCore evicts CameraX mid-pipeline, CameraX throws a SecurityException with
        // "Attempt to use camera from a different process" from submitCaptureRequest.
        val crash = SecurityException(
            "Attempt to use camera from a different process than original client",
        ).apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "android.hardware.camera2.impl.CameraDeviceImpl",
                    "submitCaptureRequest",
                    "CameraDeviceImpl.java",
                    1024,
                ),
            )
        }

        assertTrue(CrashReporter.isRecoverableArCameraCrash(crash))
    }

    @Test
    fun `recognises checkIfCameraClosedOrInError frame`() {
        val crash = IllegalStateException(
            "Attempt to use camera from a different process than original client",
        ).apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "android.hardware.camera2.impl.CameraDeviceImpl",
                    "checkIfCameraClosedOrInError",
                    "CameraDeviceImpl.java",
                    2100,
                ),
            )
        }

        assertTrue(CrashReporter.isRecoverableArCameraCrash(crash))
    }

    @Test
    fun `does not match an ordinary app crash`() {
        val crash = NullPointerException("Attempt to invoke method on a null object reference")

        assertFalse(CrashReporter.isRecoverableArCameraCrash(crash))
    }

    @Test
    fun `terminates on a self-referential cause chain`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b) // cycle: a -> b -> a

        // Must not loop forever; this chain has no camera signature.
        assertFalse(CrashReporter.isRecoverableArCameraCrash(a))
    }
}
