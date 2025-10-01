/*
 * Copyright 2024 Google LLC
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
package com.hereliesaz.graffitixr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import android.graphics.Canvas as AndroidCanvas

@Composable
fun StaticImageEditor(uiState: UiState, onUiStateChanged: (UiState) -> Unit) {
  var canvasSize by remember { mutableStateOf(IntSize.Zero) }

  val graffitiBitmap = remember {
    // Create a dummy bitmap if one isn't provided
    uiState.graffiti ?: Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
      val canvas = AndroidCanvas(this)
      val paint = Paint().apply {
        color = android.graphics.Color.BLUE
        style = Paint.Style.FILL
      }
      canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
  }

  var corners by remember {
    mutableStateOf(
      arrayOf(
        Offset(200f, 200f),
        Offset(600f, 200f),
        Offset(600f, 600f),
        Offset(200f, 600f)
      )
    )
  }

  // Center the corners when the view is first composed
  LaunchedEffect(canvasSize) {
    if (canvasSize != IntSize.Zero) {
        val centerX = canvasSize.width / 2f
        val centerY = canvasSize.height / 2f
        val halfWidth = 200f
        val halfHeight = 200f
        corners = arrayOf(
            Offset(centerX - halfWidth, centerY - halfHeight),
            Offset(centerX + halfWidth, centerY - halfHeight),
            Offset(centerX + halfWidth, centerY + halfHeight),
            Offset(centerX - halfWidth, centerY + halfHeight)
        )
    }
  }

  val transformedBitmap: ImageBitmap? = remember(corners, graffitiBitmap, canvasSize) {
    if (canvasSize == IntSize.Zero) {
        null
    } else {
        val srcBitmap = graffitiBitmap
        // Create the destination bitmap with the size of the canvas
        val dstBitmap = Bitmap.createBitmap(canvasSize.width, canvasSize.height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(dstBitmap)
        val matrix = Matrix()

        val srcPoints = floatArrayOf(
          0f, 0f,
          srcBitmap.width.toFloat(), 0f,
          srcBitmap.width.toFloat(), srcBitmap.height.toFloat(),
          0f, srcBitmap.height.toFloat()
        )

        val dstPoints = floatArrayOf(
          corners[0].x, corners[0].y,
          corners[1].x, corners[1].y,
          corners[2].x, corners[2].y,
          corners[3].x, corners[3].y
        )

        matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        val paint = Paint().apply {
          isAntiAlias = true
        }
        canvas.drawBitmap(srcBitmap, matrix, paint)
        dstBitmap.asImageBitmap()
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.DarkGray) // Use a solid color background
      .onSizeChanged {
          canvasSize = it
      }
      .pointerInput(Unit) {
        var draggingCorner: Int? = null
        detectDragGestures(
          onDragStart = { startOffset ->
            val corner = corners
              .withIndex()
              .minByOrNull { (_, corner) ->
                (corner - startOffset).getDistanceSquared()
              }
            // Increase touch radius for easier grabbing
            if (corner != null && (corner.value - startOffset).getDistanceSquared() < 900f) {
              draggingCorner = corner.index
            }
          },
          onDrag = { change, dragAmount ->
            if (draggingCorner != null) {
              val newCorners = corners.copyOf()
              newCorners[draggingCorner!!] = newCorners[draggingCorner!!] + dragAmount
              corners = newCorners
              change.consume()
            }
          },
          onDragEnd = {
            draggingCorner = null
          }
        )
      }
  ) {
    transformedBitmap?.let {
        Image(
          bitmap = it,
          contentDescription = "Transformed Graffiti"
        )
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
      for (i in corners.indices) {
        drawCircle(
          color = Color.Red,
          radius = 20f,
          center = corners[i]
        )
        drawLine(
          color = Color.Red,
          start = corners[i],
          end = corners[(i + 1) % corners.size],
          strokeWidth = 5f
        )
      }
    }
  }
}