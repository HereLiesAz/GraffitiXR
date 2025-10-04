/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.java.common.rendering

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.*
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.exceptions.*
import java.io.ByteArrayOutputStream
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the HelloAR application into a [GLSurfaceView].
 *
 * This is not a general-purpose AR renderer. This class shows how to correctly set up and draw a
 * simple AR scene that can be used as a starting point for creating a custom AR application.
 *
 * This class also provides several utility methods for common AR rendering tasks, such as display
 * geometry, ARCore session state, and transforming ARCore frame data into an image bitmap.
 */
class ArRenderer(
  val activity: Activity,
  val session: Session,
  val onBitmapAvailable: (Bitmap) -> Unit
) : GLSurfaceView.Renderer, DefaultLifecycleObserver {
  private val TAG = ArRenderer::class.java.simpleName

  //<editor-fold desc="Renderer configuration">
  lateinit var view: GLSurfaceView
  lateinit var displayRotationHelper: DisplayRotationHelper

  // </editor-fold>

  //<editor-fold desc="ARCore-specific rendering configuration">
  var installRequested = false

  private val backgroundRenderer: BackgroundRenderer = BackgroundRenderer()
  private val planeRenderer: PlaneRenderer = PlaneRenderer()
  private val pointCloudRenderer: PointCloudRenderer = PointCloudRenderer()

  //</editor-fold>

  private var hasSetTextureNames = false

  //<editor-fold desc="Lifecycle methods">
  override fun onResume(owner: LifecycleOwner) {
    super.onResume(owner)
    // ARCore requires camera permission to operate.
    var message: String? = null
    try {
      // Ensure that ARCore is installed and up to date.
      when (ArCoreApk.getInstance().requestInstall(activity, installRequested)) {
        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
          installRequested = true
          return
        }
        ArCoreApk.InstallStatus.INSTALLED -> {}
      }
      session.resume()
    } catch (e: UnavailableArcoreNotInstalledException) {
      message = "Please install ARCore"
    } catch (e: UnavailableUserDeclinedInstallationException) {
      message = "Please install ARCore"
    } catch (e: UnavailableApkTooOldException) {
      message = "Please update ARCore"
    } catch (e: UnavailableSdkTooOldException) {
      message = "Please update this app"
    } catch (e: UnavailableDeviceNotCompatibleException) {
      message = "This device does not support AR"
    } catch (e: Exception) {
      message = "Failed to create AR session"
    }

    if (message != null) {
      // TODO: Display a toast.
      // Log.e(TAG, "Error: $message")
      return
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      // Create a session if we don't already have one. This requires camera permissions.
      // Configure the session.
      val config = Config(session)
      config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
      session.configure(config)
      session.resume()
    } catch (e: CameraNotAvailableException) {
      // In some cases (such as another camera app launching) the camera may be given to
      // a different app instead. Handle this properly by showing a message and recreate the
      // session at the next iteration.
      //
      // TODO: Display a toast.
      // Log.e(TAG, "Camera not available. Please restart the app.")
      return
    }
    view.onResume()
    displayRotationHelper.onResume()
  }

  override fun onPause(owner: LifecycleOwner) {
    super.onPause(owner)
    // Note that the order matters - GLSurfaceView is paused first so that it does not try
    // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
    // still call onDrawFrame and get a NotYetAvailableException.
    displayRotationHelper.onPause()
    view.onPause()
    session.pause()
  }

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

    // Create the texture and pass it to ARCore session to be populated during update().
    backgroundRenderer.createOnGlThread()
    planeRenderer.createOnGlThread(activity, 0.0f)
    pointCloudRenderer.createOnGlThread()
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    GLES30.glViewport(0, 0, width, height)
    displayRotationHelper.onSurfaceChanged(width, height)
  }
  //</editor-fold>

  override fun onDrawFrame(gl: GL10?) {
    //<editor-fold desc="OnDrawFrame boilerplate">
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

    // -- Update per-frame state --
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session)

    // Obtain the current frame from ARCore. Frame is a transient object that holds ARCore's
    // state at a particular point in time. This is the most critical call in the AR rendering
    // loop.
    val frame =
      try {
        session.update()
      } catch (e: CameraNotAvailableException) {
        // In some cases (such as another camera app launching) the camera may be given to
        // a different app instead. Handle this properly by showing a message and recreate the
        // session at the next iteration.
        // TODO: Display a toast.
        // Log.e(TAG, "Camera not available. Please restart the app.")
        return
      }

    // Get camera and projection matrices.
    val camera = frame.camera
    val projmtx = FloatArray(16)
    camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)
    val viewmtx = FloatArray(16)
    camera.getViewMatrix(viewmtx, 0)

    // -- Draw background --
    if (frame.timestamp != 0L) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.draw(frame)
    }

    // -- Draw AR objects --
    // Draw point cloud.
    frame.acquirePointCloud().use { pointCloud ->
      pointCloudRenderer.update(pointCloud)
      pointCloudRenderer.draw(viewmtx, projmtx)
    }

    // Draw planes.
    planeRenderer.drawPlanes(
      session.getAllTrackables(Plane::class.java),
      camera.displayOrientedPose,
      projmtx
    )
  }

  fun takePhoto() {
    val frame = session.update()
    val image = frame.acquireCameraImage()
    val bitmap = imageToBitmap(image)
    image.close()
    onBitmapAvailable(bitmap)
  }

  private fun imageToBitmap(image: Image): Bitmap {
    val planes = image.planes
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    // U and V are swapped
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = android.graphics.YuvImage(
      nv21,
      ImageFormat.NV21,
      image.width,
      image.height,
      null
    )
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
    val imageBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
  }
}