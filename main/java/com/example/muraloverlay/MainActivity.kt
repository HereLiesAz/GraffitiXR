package com.example.muraloverlay

import android.Manifest
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.example.muraloverlay.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var viewModel: MuralViewModel
    private var cameraProvider: ProcessCameraProvider? = null

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { loadImage(it) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) setupCamera() else showError("Camera permission required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MuralViewModel::class.java]
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupUI()
        setupGestureControls()
        checkPermissions()
    }

    private fun setupUI() {
        with(binding) {
            // Initialize controls
            controls.opacityControl.progress = (viewModel.overlayState.opacity * 100).toInt()
            controls.contrastControl.progress = (viewModel.overlayState.contrast * 100).toInt()
            controls.saturationControl.progress = (viewModel.overlayState.saturation * 100).toInt()

            // Setup listeners
            controls.selectImageButton.setOnClickListener {
                selectImage()
            }

            controls.resetButton.setOnClickListener {
                resetOverlayState()
            }

            controls.gridToggle.setOnCheckedChangeListener { _, checked ->
                viewModel.overlayState.showGrid = checked
                overlayView.invalidate()
            }

            // Bind controls to viewModel
            arrayOf(
                controls.opacityControl to ::updateOpacity,
                controls.contrastControl to ::updateContrast,
                controls.saturationControl to ::updateSaturation
            ).forEach { (seekBar, updateFn) ->
                seekBar.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int) {
                        updateFn(progress)
                        overlayView.invalidate()
                    }
                })
            }
        }
    }

    private fun updateOpacity(progress: Int) {
        viewModel.overlayState.opacity = progress / 100f
        binding.overlayImage.alpha = viewModel.overlayState.opacity
    }

    private fun updateContrast(progress: Int) {
        viewModel.overlayState.contrast = progress / 100f
        updateColorFilter()
    }

    private fun updateSaturation(progress: Int) {
        viewModel.overlayState.saturation = progress / 100f
        updateColorFilter()
    }

    private fun updateColorFilter() {
        val contrast = viewModel.overlayState.contrast
        val saturation = viewModel.overlayState.saturation

        val contrastMatrix = ColorMatrix().apply {
            setScale(contrast, contrast, contrast, 1f)
            postConcat(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, (1 - contrast) * 128,
                0f, 1f, 0f, 0f, (1 - contrast) * 128,
                0f, 0f, 1f, 0f, (1 - contrast) * 128,
                0f, 0f, 0f, 1f, 0f
            )))
        }

        val saturationMatrix = ColorMatrix()
        saturationMatrix.setSaturation(saturation)

        contrastMatrix.postConcat(saturationMatrix)
        binding.overlayImage.colorFilter = ColorMatrixColorFilter(contrastMatrix)
    }

    private fun resetOverlayState() {
        with(viewModel.overlayState) {
            opacity = 0.5f
            contrast = 1.0f
            saturation = 1.0f
            scale = 1.0f
            translationX = 0f
            translationY = 0f
        }
        updateImageTransforms()
    }

    private fun updateImageTransforms() {
        viewModel.imageMatrix.apply {
            reset()
            postScale(
                viewModel.overlayState.scale,
                viewModel.overlayState.scale,
                binding.overlayImage.width / 2f,
                binding.overlayImage.height / 2f
            )
            postTranslate(
                viewModel.overlayState.translationX,
                viewModel.overlayState.translationY
            )
        }
        binding.overlayImage.imageMatrix = viewModel.imageMatrix
    }

    private fun selectImage() {
        imagePicker.launch("image/*")
    }

    private fun loadImage(uri: Uri) {
        Glide.with(this)
            .asBitmap()
            .load(uri)
            .override(binding.cameraView.width, binding.cameraView.height)
            .centerInside()
            .into(binding.overlayImage)

        viewModel.overlayState.imageUri = uri
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            setupCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setTargetRotation(binding.cameraView.display.rotation)
            .build()
            .also { it.setSurfaceProvider(binding.cameraView.surfaceProvider) }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        } catch (exc: Exception) {
            showError("Failed to bind camera use cases: ${exc.message}")
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.e("MuralOverlay", message)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

abstract class SimpleSeekBarListener : SeekBar.OnSeekBarChangeListener {
    override fun onStartTrackingTouch(seekBar: SeekBar) {}
    override fun onStopTrackingTouch(seekBar: SeekBar) {}
}