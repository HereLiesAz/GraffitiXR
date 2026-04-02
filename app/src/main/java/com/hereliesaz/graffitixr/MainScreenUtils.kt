package com.hereliesaz.graffitixr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.scale
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.design.theme.HotPink
import com.hereliesaz.graffitixr.design.theme.Cyan
import kotlin.math.atan2

@Composable
fun OffscreenIndicators(
    uiState: EditorUiState,
    arUiState: ArUiState,
    screenSize: IntSize,
    modifier: Modifier = Modifier
) {
    if (screenSize.width <= 0 || screenSize.height <= 0) return

    // 1. Indicator for Active Layer (HotPink)
    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
    if (activeLayer != null) {
        val centerX = screenSize.width / 2f
        val centerY = screenSize.height / 2f
        
        val layerCenterX = centerX + activeLayer.offset.x
        val layerCenterY = centerY + activeLayer.offset.y
        
        if (layerCenterX < 0 || layerCenterX > screenSize.width || layerCenterY < 0 || layerCenterY > screenSize.height) {
            DirectionalIndicator(
                angle = Math.toDegrees(atan2((layerCenterY - centerY).toDouble(), (layerCenterX - centerX).toDouble())).toFloat(),
                label = "Active Layer",
                color = HotPink,
                modifier = modifier
            )
        }
    }

    // 2. Indicator for Target (Cyan) - AR Mode only
    if (uiState.editorMode == EditorMode.AR && arUiState.isAnchorEstablished) {
        val relDir = arUiState.anchorRelativeDirection
        if (relDir != null) {
            val (lx, ly, lz) = relDir
            
            // If lz > 0, it's behind the camera. 
            // If lz < 0, it's in front.
            // We want the indicator if it's offscreen.
            
            val isOffscreen = lz > 0 || Math.abs(lx) > 0.8f || Math.abs(ly) > 0.8f
            
            if (isOffscreen) {
                // localY is UP, so we negate it for screen-space (down is positive)
                val angle = Math.toDegrees(atan2(-ly.toDouble(), lx.toDouble())).toFloat()
                DirectionalIndicator(
                    angle = angle,
                    label = "Wall Target",
                    color = Cyan,
                    modifier = modifier,
                    distance = if (lz > 0) "BEHIND" else null
                )
            }
        }
    }
}

@Composable
private fun DirectionalIndicator(
    angle: Float,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    distance: String? = null
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .rotate(angle)
                .offset(y = (-150).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier.rotate(-angle), // Keep text upright
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                if (distance != null) {
                    Text(
                        text = distance,
                        color = color,
                        fontSize = 8.sp
                    )
                }
            }
        }
    }
}
