package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap

data class LoadedProject(
    val projectData: ProjectData,
    val targetImages: List<Bitmap>,
    val thumbnail: Bitmap? = null
)
