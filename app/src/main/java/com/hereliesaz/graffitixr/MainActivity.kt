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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.az.navrail.AzNavRail
import com.az.navrail.AzNavRailItem

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val graffitiViewModel: GraffitiViewModel = viewModel()
      val uiState by graffitiViewModel.uiState.collectAsState()

      Row(modifier = Modifier.fillMaxSize()) {
        AzNavRail(
          items = listOf(
            AzNavRailItem.Cycler(
              title = "Mode",
              options = AppMode.values().map { it.name.replace('_', ' ').lowercase().replaceFirstChar { char -> char.uppercase() } },
              selectedOption = uiState.currentMode.name.replace('_', ' ').lowercase().replaceFirstChar { char -> char.uppercase() },
              onOptionSelected = { option ->
                val mode = AppMode.valueOf(option.replace(' ', '_').uppercase())
                graffitiViewModel.setCurrentMode(mode)
              },
              icon = {
                when (uiState.currentMode) {
                  AppMode.MOCK_UP -> Icons.Default.Edit
                  AppMode.NON_AR_CAMERA -> Icons.Default.Camera
                  AppMode.AR -> Icons.Default.ViewInAr
                }
              }
            )
          )
        )
        Box(modifier = Modifier.weight(1f)) {
          when (uiState.currentMode) {
            AppMode.MOCK_UP -> {
              StaticImageEditor(
                uiState = uiState,
                onUiStateChanged = { graffitiViewModel.setUiState(it) }
              )
            }
            AppMode.NON_AR_CAMERA -> {
              CameraView()
            }
            AppMode.AR -> {
              ARView()
            }
          }
        }
      }
    }
  }
}