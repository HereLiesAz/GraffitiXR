package com.hereliesaz.graffitixr

import android.graphics.Bitmap
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
import androidx.xr.compose.PlaneFindingMode
import androidx.xr.compose.rememberHitTestResult
import androidx.xr.compose.runtime.rememberPlaneFindingMode
import coil.imageLoader
import coil.request.ImageRequest
import io.github.sceneview.loaders.loadTexture
import io.github.sceneview.material.Material
import io.github.sceneview.material.MaterialInstance
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.Node
import io.github.sceneview.primitive.Mesh
import io.github.sceneview.primitive.Plane
import io.github.sceneview.primitive.Sphere
import io.github.sceneview.rememberMaterial
import io.github.sceneview.rememberNodes

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
    var texture by remember { mutableStateOf<MaterialInstance.Texture?>(null) }

    // Asynchronously load the selected image URI into a texture.
    LaunchedEffect(uiState.imageUri) {
        texture = null
        uiState.imageUri?.let { uri ->
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false) // Software bitmap is required for texture loading.
                .target { result ->
                    val bitmap = (result as BitmapDrawable).bitmap
                    // Load the bitmap into a SceneView texture.
                    texture = loadTexture(context, bitmap)
                }
                .build()
            context.imageLoader.enqueue(request)
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
                        position = Position(pose.position.x, pose.position.y, pose.position.z),
                        primitive = Plane(
                            center = Position(0f, 0.01f, 0f),
                            size = Size(0.1f, 0.1f),
                            material = CursorMaterial()
                        )
                    )
                )
            }

            // Display a node for each marker the user has placed.
            uiState.markerPoses.forEach { pose ->
                add(
                    Node(
                        position = Position(pose.position.x, pose.position.y, pose.position.z),
                        primitive = Sphere(radius = 0.02f, material = MarkerMaterial())
                    )
                )
            }

            // If four markers are placed and a texture is loaded, render the projected image.
            if (uiState.markerPoses.size == 4 && texture != null) {
                val material = rememberMaterial(baseColorTexture = texture)
                val vertices = uiState.markerPoses.map {
                    // Convert from AndroidX Pose to SceneView Position
                    Position(it.position.x, it.position.y, it.position.z)
                }
                // Define the two triangles that form the quad.
                val triangles = listOf(
                    // First triangle (top-left, top-right, bottom-right)
                    listOf(0, 1, 2),
                    // Second triangle (top-left, bottom-right, bottom-left)
                    listOf(0, 2, 3)
                )

                add(
                    Node(
                        primitive = Mesh(
                            vertices = vertices,
                            uvs = listOf(
                                // Map the corners of the texture to the markers.
                                floatArrayOf(0f, 0f), // Top-left
                                floatArrayOf(1f, 0f), // Top-right
                                floatArrayOf(1f, 1f), // Bottom-right
                                floatArrayOf(0f, 1f)  // Bottom-left
                            ),
                            triangles = triangles,
                            material = material
                        )
                    )
                )
            }
        }
    )
}

@Composable
private fun CursorMaterial(): MaterialInstance {
    return rememberMaterial(Material(baseColor = Color.Green.copy(alpha = 0.5f)))
}

@Composable
private fun MarkerMaterial(): MaterialInstance {
    return rememberMaterial(Material(baseColor = Color.Red.copy(alpha = 0.7f)))
}