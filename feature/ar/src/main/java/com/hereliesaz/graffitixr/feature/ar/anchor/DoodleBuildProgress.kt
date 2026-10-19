package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How far the doodle's close-ups ([CloseUpFeatures]) are along, for the progress pill shown
 * while a target is built: which stage, and how far through it, or null when none is being built.
 * Process-wide like the native engine the doodle goes to; one build at a time.
 */
object DoodleBuildProgress {
    /**
     * The build runs in [stages]; this is [stage] (1-based) of them with [stageDone] of [stageTotal]
     * of that stage's steps done, and [done] of [total] steps done across all stages. [fraction] is
     * the CURRENT stage's progress, not the whole build's.
     */
    data class Step(
        val stage: Int, val stages: Int,
        val stageDone: Int, val stageTotal: Int,
        val done: Int, val total: Int,
    ) {
        val fraction: Float get() = if (stageTotal <= 0) 0f else stageDone.toFloat() / stageTotal
    }

    private val _state = MutableStateFlow<Step?>(null)
    val state: StateFlow<Step?> = _state.asStateFlow()

    internal fun set(step: Step?) {
        _state.value = step
    }
}
