// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt
package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.Surface
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.common.util.ImageProcessingUtils
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager,
    private val isCaptureRequested: () -> Boolean,
    private val onTargetCaptured: (Bitmap?, FloatArray?, Int, Int, FloatArray?) -> Unit,
    private val onTrackingUpdated: (Boolean, Int) -> Unit
) : GLSurfaceView.Renderer {

    private var session: Session? = null
    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val backgroundRenderer = BackgroundRenderer()

    private val frameChannel = Channel<Boolean>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        backgroundScope.launch {
            frameChannel.consumeAsFlow().collect { isTracking ->
                slamManager.setArCoreTrackingState(isTracking)
                val splatCount = slamManager.getSplatCount()
                onTrackingUpdated(isTracking, splatCount)
            }
        }
    }

    fun setSession(session: Session) {
        this.session = session
    }

    fun updateFlashlight(isOn: Boolean) {
        try {
            session?.let { activeSession ->
                val config = activeSession.config
                config.focusMode = Config.FocusMode.AUTO
                val flashlightModeClass = Class.forName("com.google.ar.core.Config\$FlashMode")
                val method = config.javaClass.getMethod("setFlashMode", flashlightModeClass)

                val flashModeEnum = if (isOn) {
                    flashlightModeClass.getField("TORCH").get(null)
                } else {
                    flashlightModeClass.getField("OFF").get(null)
                }

                method.invoke(config, flashModeEnum)
                activeSession.configure(config)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
        slamManager.initGl()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(Surface.ROTATION_0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return

        try {
            currentSession.setCameraTextureName(backgroundRenderer.textureId)
            val frame = currentSession.update()
            val camera = frame.camera

            backgroundRenderer.draw(frame)

            val viewMatrix = FloatArray(16)
            val projMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

            slamManager.updateCamera(viewMatrix, projMatrix)

            val isTracking = camera.trackingState == TrackingState.TRACKING
            frameChannel.trySend(isTracking)

            slamManager.draw()

            if (isCaptureRequested()) {
                frame.acquireCameraImage().use { image ->
                    val bmp = ImageProcessingUtils.yuvToRgbBitmap(image)
                    onTargetCaptured(bmp, null, image.width, image.height, null)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun destroy() {
        backgroundScope.cancel("Renderer detached and destroyed.")
        session = null
    }
}