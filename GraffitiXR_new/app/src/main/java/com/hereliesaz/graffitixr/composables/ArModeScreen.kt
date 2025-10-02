package com.hereliesaz.graffitixr.composables

import android.Manifest
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.xr.compose.*
import androidx.xr.compose.hitTest
import androidx.xr.compose.services.LocalPlaneService
import androidx.xr.core.Pose
import androidx.xr.scenecore.materials.Material
import androidx.xr.scenecore.materials.Texture
import androidx.xr.scenecore.meshes.Mesh
import androidx.xr.scenecore.meshes.geometry
import androidx.xr.scenecore.nodes.Node
import com.google.android.filament.Box
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.UiState
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Composable for the AR experience.
 * This screen will handle the ArScene, hit-testing, and rendering of AR content.
 *
 * @param uiState The current state of the UI.
 * @param onArMarkerPlaced Callback for when an AR marker is placed.
 */
@Composable
fun ArModeScreen(
    uiState: UiState,
    onArMarkerPlaced: (Pose) -> Unit
) {
    ArEnablePermissionHelper()
    Box(modifier = Modifier.fillMaxSize()) {
        TryUpdateSession { session ->
            val frame by session.frame.collectAsState()
            frame?.let { fr ->
                ArFrame(
                    fr,
                    onTap = {
                        val hitResult = fr.hitTest(it).firstOrNull()
                        if (hitResult != null) {
                            onArMarkerPlaced(hitResult.pose)
                        }
                    }
                ) {
                    val planes = LocalPlaneService.current.planes.collectAsState()
                    planes.value.forEach { plane ->
                        if (plane.trackingState == TrackingState.TRACKING) {
                            // Visualize planes if desired
                        }
                    }

                    // Render markers
                    uiState.arMarkers.forEach { pose ->
                        Node(pose = pose) {
                            // A simple cube to represent the marker
                            Node(scale = com.google.android.filament.math.float3(0.02f, 0.02f, 0.02f)) {
                                mesh = Mesh.defaultCube
                                material = Material.defaultOpaque
                            }
                        }
                    }

                    // Render the image quad
                    if (uiState.arMarkers.size == 4 && uiState.overlayImageUri != null) {
                        val context = LocalContext.current
                        val material by remember(uiState.overlayImageUri, uiState.opacity, uiState.contrast, uiState.saturation) {
                            mutableStateOf(
                                Material(
                                    baseColorTexture = Texture(
                                        BitmapFactory.decodeStream(
                                            context.contentResolver.openInputStream(uiState.overlayImageUri)
                                        )
                                    ),
                                    alpha = uiState.opacity,
                                    // Contrast and saturation would be applied via a custom shader,
                                    // which is complex and beyond the scope of this implementation.
                                    // For now, we will just apply opacity.
                                )
                            )
                        }

                        val mesh by remember(uiState.arMarkers) {
                            mutableStateOf(createQuadMesh(uiState.arMarkers))
                        }

                        Node {
                            this.mesh = mesh
                            this.material = material
                        }
                    }
                }
            }
        }
    }
}

private fun createQuadMesh(markers: List<Pose>): Mesh {
    val vertices = markers.flatMap {
        listOf(it.position.x, it.position.y, it.position.z)
    }.toFloatArray()

    val uvs = floatArrayOf(
        0f, 1f, // Top-left
        1f, 1f, // Top-right
        1f, 0f, // Bottom-right
        0f, 0f  // Bottom-left
    )

    val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

    return Mesh.builder()
        .geometry(
            vertexBuffer = FloatBuffer.wrap(vertices),
            uvBuffer = FloatBuffer.wrap(uvs),
            indexBuffer = ShortBuffer.wrap(indices)
        )
        .build()
}