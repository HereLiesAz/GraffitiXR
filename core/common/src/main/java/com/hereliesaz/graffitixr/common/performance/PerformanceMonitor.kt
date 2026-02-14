package com.hereliesaz.graffitixr.common.performance

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Utility for monitoring application performance (FPS, memory, latency).
 */
object PerformanceMonitor {

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _frameTimeMs = MutableStateFlow(0f)
    val frameTimeMs: StateFlow<Float> = _frameTimeMs.asStateFlow()

    private val _memoryUsageMb = MutableStateFlow(0L)
    val memoryUsageMb: StateFlow<Long> = _memoryUsageMb.asStateFlow()

    private var lastFrameTime = 0L
    private var frameCount = 0
    private var lastFpsUpdateTime = 0L

    fun onFrame() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (lastFrameTime > 0) {
            val frameDuration = (now - lastFrameTime) / 1_000_000f
            _frameTimeMs.value = frameDuration
        }
        lastFrameTime = now

        frameCount++
        if (now - lastFpsUpdateTime >= 1_000_000_000L) { // Update every second
            _fps.value = frameCount.toFloat()
            frameCount = 0
            lastFpsUpdateTime = now
            updateMemoryUsage()
        }
    }

    private fun updateMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        _memoryUsageMb.value = usedMemory
    }
}
