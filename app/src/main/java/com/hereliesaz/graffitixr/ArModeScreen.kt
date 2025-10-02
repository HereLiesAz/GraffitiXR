package com.hereliesaz.graffitixr

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.xr.compose.ArScene
import androidx.xr.compose.HitTestResult
import androidx.xr.compose.LocalEngine
import androidx.xr.compose.PlaneFindingMode
import androidx.xr.compose.rememberHitTestResult
import androidx.xr.compose.rememberNodes
import androidx.xr.compose.rememberPlaneFindingMode
import androidx.xr.scenecore.Node
import androidx.xr.scenecore.assets.MaterialProvider
import androidx.xr.scenecore.loaders.loadTexture
import androidx.xr.scenecore.math.Position
import androidx.xr.scenecore.math.Size
import androidx.xr.scenecore.primitive.Mesh
import androidx.xr.scenecore.primitive.Plane
import androidx.xr.scenecore.primitive.Sphere
import coil.imageLoader
import coil.request.ImageRequest
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture

/**
 * A composable function that encapsulates the Augmented Reality (AR) mode of the application.
 *
 * This screen is responsible for:
 * - Displaying the live camera feed through the `ArScene`.
 * - Performing continuous hit-testing against real-world surfaces.
 * - Allowing the user to tap to place markers in the scene.
 * - Rendering the selected image onto a 3D mesh defined by the four markers.
 *
 * @param modifier A [Modifier] for this composable.
 * @param viewModel The [MainViewModel] instance that holds the state and handles AR events.
 */
@Composable
fun ArModeScreen(modifier: Modifier = Modifier, viewModel: MainViewModel) {
    val hitTestResult = rememberHitTestResult()
    val planeFindingMode = rememberPlaneFindingMode(PlaneFindingMode.Horizontal)
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val engine = LocalEngine.current
    var texture by remember { mutableStateOf<Texture?>(null) }

    // Asynchronously load the selected image URI into a texture.
    LaunchedEffect(uiState.imageUri, engine) {
        texture = null // Reset texture when URI changes
        val currentUri = uiState.imageUri
        if (currentUri != null) {
            val request = ImageRequest.Builder(context)
                .data(currentUri)
                .allowHardware(false) // Software bitmap is required for texture loading.
                .build()
            try {
                val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    texture = engine.loadTexture(bitmap)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    ArScene(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { viewModel.onAddMarker() })
        },
        hitTestResult = hitTestResult,
        planeFindingMode = planeFindingMode,
        onHitTestResult = { result: HitTestResult -> viewModel.onHitTestResult(result.pose) },
        nodes = rememberNodes {
            // Display a cursor at the current hit test location.
            hitTestResult.pose?.let { pose ->
                add(
                    Node(
                        engine = engine,
                        position = Position(pose.position[0], pose.position[1], pose.position[2]),
                        primitive = Plane(
                            engine = engine,
                            center = Position(0f, 0.01f, 0f),
                            size = Size(0.1f, 0.1f),
                            materialInstance = CursorMaterial()
                        )
                    )
                )
            }

            // Display a node for each marker the user has placed.
            uiState.markerPoses.forEach { pose ->
                add(
                    Node(
                        engine = engine,
                        position = Position(pose.position[0], pose.position[1], pose.position[2]),
                        primitive = Sphere(
                            engine = engine,
                            radius = 0.02f,
                            materialInstance = MarkerMaterial()
                        )
                    )
                )
            }

            // If four markers are placed and a texture is loaded, render the projected image.
            if (uiState.markerPoses.size == 4) {
                val currentTexture = texture
                if (currentTexture != null) {
                    val material = remember(engine, currentTexture) {
                        MaterialProvider.getInstance(engine).createUnlitMaterialInstance(baseColorMap = currentTexture)
                    }
                    val vertices = uiState.markerPoses.map { markerPose ->
                        Position(markerPose.position[0], markerPose.position[1], markerPose.position[2])
                    }
                    val triangles = listOf(
                        listOf(0, 1, 2),
                        listOf(0, 2, 3)
                    )

                    add(
                        Node(
                            engine = engine,
                            primitive = Mesh(
                                engine = engine,
                                vertices = vertices,
                                uvs = listOf(
                                    floatArrayOf(0f, 0f), // Top-left
                                    floatArrayOf(1f, 0f), // Top-right
                                    floatArrayOf(1f, 1f), // Bottom-right
                                    floatArrayOf(0f, 1f)  // Bottom-left
                                ),
                                triangles = triangles,
                                materialInstance = material
                            )
                        )
                    )
                }
            }
        }
    )
}

@Composable
private fun CursorMaterial(): MaterialInstance {
    val engine = LocalEngine.current
    return remember(engine) {
        MaterialProvider.getInstance(engine).createColoredMaterialInstance(
            color = Color.Green.copy(alpha = 0.5f),
            isLit = false
        )
    }
}

@Composable
private fun MarkerMaterial(): MaterialInstance {
    val engine = LocalEngine.current
    return remember(engine) {
        MaterialProvider.getInstance(engine).createColoredMaterialInstance(
            color = Color.Red.copy(alpha = 0.7f),
            isLit = false
        )
    }
}
