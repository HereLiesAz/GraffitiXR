package com.hereliesaz.graffitixr

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.xr.compose.ArScene
import androidx.xr.compose.HitTestResult
import androidx.xr.compose.PlaneFindingMode
import androidx.xr.compose.rememberHitTestResult
import androidx.xr.compose.runtime.rememberPlaneFindingMode
import io.github.sceneview.material.Material
import io.github.sceneview.material.MaterialInstance
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import io.github.scene.primitive.Plane
import io.github.sceneview.rememberMaterial
import io.github.sceneview.rememberNodes

/**
 * A composable function that encapsulates the Augmented Reality (AR) mode of the application.
 *
 * This screen is responsible for:
 * - Displaying the live camera feed through the `ArScene`.
 * - Performing continuous hit-testing against real-world surfaces.
 * - Displaying a cursor node at the hit test location to guide the user.
 * - Notifying the [MainViewModel] of the latest hit test result.
 *
 * @param modifier A [Modifier] for this composable.
 * @param viewModel The [MainViewModel] instance to which AR events (like hit test results)
 *   are reported.
 */
@Composable
fun ArModeScreen(modifier: Modifier = Modifier, viewModel: MainViewModel) {
    val hitTestResult = rememberHitTestResult()
    val planeFindingMode = rememberPlaneFindingMode(PlaneFindingMode.Horizontal)

    // The core of the AR experience. It handles the camera feed and AR session.
    ArScene(
        modifier = modifier,
        hitTestResult = hitTestResult,
        planeFindingMode = planeFindingMode,
        // Inform the ViewModel about the latest hit test pose.
        onHitTestResult = { result: HitTestResult ->
            viewModel.onHitTestResult(result.pose)
        },
        nodes = rememberNodes {
            // Display a cursor at the hit test location.
            hitTestResult.pose?.let { pose ->
                add(
                    Node(
                        position = Position(pose.position.x, pose.position.y, pose.position.z),
                        // The cursor is a small, semi-transparent green plane.
                        primitive = Plane(
                            center = Position(0f, 0.01f, 0f), // Slightly above the detected plane
                            size = Size(0.1f, 0.1f),
                            material = CursorMaterial()
                        )
                    )
                )
            }
        }
    )
}

/**
 * A private helper composable that creates a reusable [MaterialInstance] for the AR cursor.
 *
 * @return A semi-transparent green material instance.
 */
@Composable
private fun CursorMaterial(): MaterialInstance {
    return rememberMaterial(
        Material(
            baseColor = Color.Green.copy(alpha = 0.5f)
        )
    )
}