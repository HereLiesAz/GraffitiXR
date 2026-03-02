package com.hereliesaz.graffitixr.feature.ar.rendering

import com.google.ar.core.PointCloud
import java.nio.FloatBuffer

class FeaturePointRenderer {

    fun createOnGlThread() {
        // Shader compilation and buffer generation logic
    }

    fun update(pointCloud: PointCloud) {
        // Ingests ARCore's transient PointCloud
    }

    fun update(points: FloatBuffer, count: Int) {
        // Ingests persistent engine coordinates mapped directly from C++ memory
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        // Standard projection rendering
    }
}
