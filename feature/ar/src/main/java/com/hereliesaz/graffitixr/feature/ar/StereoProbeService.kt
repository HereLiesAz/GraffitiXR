package com.hereliesaz.graffitixr.feature.ar

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.hereliesaz.graffitixr.feature.ar.eval.DepthLookup
import timber.log.Timber
import java.util.EnumSet

/**
 * Runs the hardware-stereo capability probe in an isolated ":probe" process (declared in the
 * manifest). On a device whose ARCore motion-stereo is broken, the native VIO threads thrash the CPU
 * and never converge; isolating that work in a throwaway background process keeps the foreground UI
 * process responsive (the scheduler favours the foreground app over this background one), so a hang
 * here can never ANR the app — at worst Android kills this process and the client falls back to mono.
 *
 * Protocol: bind, then send [MSG_RUN_PROBE] with `replyTo` set. The service runs a short forced-stereo
 * session on a worker thread and replies with [MSG_RESULT] whose `arg1` is 1 (the dual lenses actually
 * triangulate depth) or 0 (they don't / setup failed).
 *
 * The verdict deliberately tests *depth triangulation*, not just dual-lens presence or tracking: a
 * device can expose a hardware-stereo camera config and even reach VIO TRACKING while its two lenses
 * never produce a usable depth map. Adopting stereo on such a device buys nothing (and historically
 * pegged the CPU into an ANR). So we only call a device stereo-capable once it has handed us a real,
 * populated depth image with the Depth API enabled under the forced-stereo config.
 */
class StereoProbeService : Service() {

    companion object {
        const val MSG_RUN_PROBE = 1
        const val MSG_RESULT = 2
        /**
         * Total window to reach TRACKING *and* hand back a valid depth image. Depth needs a second or
         * two to converge after tracking starts, so this is larger than a tracking-only probe.
         */
        const val PROBE_TIMEOUT_MS = 6_000L
        /** Fraction of a sampled grid that must carry valid metric depth to count as triangulation. */
        private const val MIN_VALID_DEPTH_FRACTION = 0.25f
    }

    private lateinit var worker: HandlerThread
    private lateinit var workerHandler: Handler

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == MSG_RUN_PROBE) {
            val reply = msg.replyTo
            // Run the probe off this process's main thread so even this sacrificial process can't ANR.
            workerHandler.post {
                val capable = runStereoProbe()
                try {
                    reply?.send(Message.obtain(null, MSG_RESULT, if (capable) 1 else 0, 0))
                } catch (e: RemoteException) {
                    // Client already gone (timed out / unbound). Nothing to do.
                }
            }
            true
        } else {
            false
        }
    })

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("StereoProbe").apply { start() }
        workerHandler = Handler(worker.looper)
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    /**
     * Blocking probe. Returns true iff a forced-stereo session both reaches TRACKING *and* produces a
     * populated depth image within the timeout — i.e. the dual lenses actually triangulate depth.
     * Tracking alone is the weak signal we used to trust; it let through devices that list a stereo
     * config but never compute disparity.
     */
    private fun runStereoProbe(): Boolean {
        var egl: ProbeEgl? = null
        var probe: Session? = null
        try {
            probe = Session(this)
            // No Depth API support means there is no depth to triangulate from the lenses, period.
            if (!probe.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                Timber.i("ARDIAG depth-triangulation probe: Depth API unsupported -> incapable")
                return false
            }
            val cfg = Config(probe).apply {
                focusMode = Config.FocusMode.AUTO
                // BLOCKING, matching the live session: LATEST_CAMERA_IMAGE drops frames the init-time
                // depth provider needs, which jams VIO into kNotTracking. With it, the probe would
                // never reach TRACKING and would falsely classify every device as incapable.
                updateMode = Config.UpdateMode.BLOCKING
                // Enable depth: this is the capability under test. The probe lives in a throwaway
                // ":probe" process, so even if AUTOMATIC depth thrashes here it can't starve the UI.
                depthMode = Config.DepthMode.AUTOMATIC
                planeFindingMode = Config.PlaneFindingMode.DISABLED
            }
            probe.configure(cfg)
            val stereoConfigs = probe.getSupportedCameraConfigs(CameraConfigFilter(probe).apply {
                facingDirection = CameraConfig.FacingDirection.BACK
                stereoCameraUsage = EnumSet.of(CameraConfig.StereoCameraUsage.REQUIRE_AND_USE)
            })
            if (stereoConfigs.isEmpty()) {
                Timber.i("ARDIAG depth-triangulation probe: no hardware-stereo config -> incapable")
                return false
            }
            probe.cameraConfig = stereoConfigs[0]

            egl = ProbeEgl()
            probe.setCameraTextureName(egl.cameraTextureId)
            probe.resume()

            // Monotonic clock: a wall-clock jump (NTP sync) must not extend or truncate the window.
            val deadline = android.os.SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MS
            var reachedTracking = false
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val frame = try {
                    probe.update()
                } catch (e: Exception) {
                    Timber.w(e, "ARDIAG depth-triangulation probe: update() failed")
                    return false
                }
                if (frame.camera.trackingState != TrackingState.TRACKING) {
                    Thread.sleep(33)
                    continue
                }
                reachedTracking = true
                // Tracking is reached; now demand a real depth map. acquireDepthImage16Bits throws
                // NotYetAvailableException until depth has converged, so keep pumping until then.
                try {
                    frame.acquireDepthImage16Bits().use { depth ->
                        if (depthMapHasValidTriangulation(depth)) {
                            Timber.i("ARDIAG depth-triangulation probe: valid depth map -> capable")
                            return true
                        }
                    }
                } catch (e: NotYetAvailableException) {
                    // Depth not converged yet — keep going until the deadline.
                } catch (e: Exception) {
                    Timber.w(e, "ARDIAG depth-triangulation probe: depth acquire failed")
                }
                Thread.sleep(33)
            }
            Timber.i(
                "ARDIAG depth-triangulation probe: tracking=$reachedTracking but no valid depth map " +
                    "within ${PROBE_TIMEOUT_MS}ms -> incapable"
            )
            return false
        } catch (e: Exception) {
            Timber.w(e, "ARDIAG depth-triangulation probe: setup failed -> incapable")
            return false
        } finally {
            try { probe?.pause() } catch (_: Exception) {}
            try { probe?.close() } catch (_: Exception) {}
            egl?.release()
        }
    }

    /**
     * True if [depth] carries enough valid metric samples to count as real dual-lens triangulation. A
     * device that lists a stereo config but can't compute disparity hands back an empty/all-zero map,
     * so we sample an 11x11 grid across the frame and require at least [MIN_VALID_DEPTH_FRACTION] of
     * the cells to read back a plausible distance (the per-cell patch median rejects stray pixels).
     */
    private fun depthMapHasValidTriangulation(depth: android.media.Image): Boolean {
        val plane = depth.planes.firstOrNull() ?: return false
        val w = depth.width
        val h = depth.height
        if (w <= 0 || h <= 0) return false
        val grid = 11
        var valid = 0
        for (gy in 0 until grid) {
            for (gx in 0 until grid) {
                val u = (gx + 0.5f) / grid
                val v = (gy + 0.5f) / grid
                if (DepthLookup.depthMetersAtPatch(plane.buffer, plane.rowStride, w, h, u, v, radius = 1) > 0f) {
                    valid++
                }
            }
        }
        return valid >= grid * grid * MIN_VALID_DEPTH_FRACTION
    }

    /** Minimal offscreen EGL context (1x1 pbuffer) with one GL_TEXTURE_EXTERNAL_OES texture, so the
     *  probe session has somewhere to bind camera frames while we pump [Session.update]. */
    private class ProbeEgl {
        private var display: android.opengl.EGLDisplay = android.opengl.EGL14.EGL_NO_DISPLAY
        private var ctx: android.opengl.EGLContext = android.opengl.EGL14.EGL_NO_CONTEXT
        private var surface: android.opengl.EGLSurface = android.opengl.EGL14.EGL_NO_SURFACE
        val cameraTextureId: Int

        init {
            // Validate every EGL step: a failure on an odd device/emulator surfaces as an exception
            // (probe falls back to mono) rather than running GL on an invalid context.
            display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            if (display == android.opengl.EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
            val version = IntArray(2)
            if (!android.opengl.EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw RuntimeException("eglInitialize failed")
            }
            val cfgAttribs = intArrayOf(
                android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                android.opengl.EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!android.opengl.EGL14.eglChooseConfig(display, cfgAttribs, 0, configs, 0, 1, numConfig, 0) ||
                numConfig[0] <= 0 || configs[0] == null) {
                throw RuntimeException("eglChooseConfig failed")
            }
            val ctxAttribs = intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE)
            ctx = android.opengl.EGL14.eglCreateContext(display, configs[0], android.opengl.EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (ctx == android.opengl.EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
            val surfAttribs = intArrayOf(android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE)
            surface = android.opengl.EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0)
            if (surface == android.opengl.EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreatePbufferSurface failed")
            if (!android.opengl.EGL14.eglMakeCurrent(display, surface, surface, ctx)) {
                throw RuntimeException("eglMakeCurrent failed")
            }
            val tex = IntArray(1)
            android.opengl.GLES20.glGenTextures(1, tex, 0)
            android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            cameraTextureId = tex[0]
        }

        fun release() {
            if (display != android.opengl.EGL14.EGL_NO_DISPLAY) {
                android.opengl.EGL14.eglMakeCurrent(
                    display, android.opengl.EGL14.EGL_NO_SURFACE,
                    android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT
                )
                if (surface != android.opengl.EGL14.EGL_NO_SURFACE) android.opengl.EGL14.eglDestroySurface(display, surface)
                if (ctx != android.opengl.EGL14.EGL_NO_CONTEXT) android.opengl.EGL14.eglDestroyContext(display, ctx)
                android.opengl.EGL14.eglTerminate(display)
            }
        }
    }
}
