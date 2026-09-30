package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Resolves whether ARCore is supported (and installed/updated) on the current
 * device. The Play Store no longer filters the listing to ARCore-eligible
 * devices — see app/src/main/AndroidManifest.xml — so the app must check at
 * runtime before attempting to construct an ARCore Session.
 *
 * Use [check] from a coroutine on app start. This reports **ARCore** capability only; it must not
 * be treated as the final answer to "can this device use GraffitiXR AR?". Once the standalone
 * SphereSLAM backend is complete, unsupported ARCore devices must route to that backend instead of
 * hiding AR mode. The current UI still uses this result directly, which is a temporary limitation.
 */
object ArAvailabilityChecker {

    enum class Result { Supported, Unsupported, NeedsInstallOrUpdate }

    suspend fun check(context: Context): Result {
        var availability: ArCoreApk.Availability = try {
            ArCoreApk.getInstance().checkAvailability(context)
        } catch (t: Throwable) {
            Timber.w(t, "ArCoreApk.checkAvailability threw; treating as unsupported")
            return Result.Unsupported
        }

        // checkAvailability() may return UNKNOWN_CHECKING the first time it's
        // called on a device that doesn't have ARCore installed yet — Play
        // Services for AR queries the network. Poll briefly until it resolves.
        val deadlineMs = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS
        while (availability.isTransient && System.currentTimeMillis() < deadlineMs) {
            delay(POLL_INTERVAL_MS)
            availability = try {
                ArCoreApk.getInstance().checkAvailability(context)
            } catch (t: Throwable) {
                Timber.w(t, "ArCoreApk.checkAvailability threw mid-poll")
                return Result.Unsupported
            }
        }

        return when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> Result.Supported
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> Result.NeedsInstallOrUpdate
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> Result.Unsupported
            // Still UNKNOWN after timeout: classify ARCore itself as unsupported.
            // Current UI hides AR mode here, but the intended architecture routes
            // to standalone SphereSLAM once that backend is complete.
            else -> Result.Unsupported
        }
    }

    private const val POLL_INTERVAL_MS = 200L
    private const val RESOLVE_TIMEOUT_MS = 4_000L
}
