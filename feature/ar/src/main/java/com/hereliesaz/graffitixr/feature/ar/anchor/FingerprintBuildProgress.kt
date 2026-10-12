package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How far the fingerprint's close-ups ([CloseUpFeatures]) are along, for the progress bar shown
 * while a target is built: [Step.done] of [Step.total] close-ups, or null when none is being built.
 * Process-wide like the native engine the fingerprint goes to; one build at a time.
 */
object FingerprintBuildProgress {
    data class Step(val done: Int, val total: Int) {
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    private val _state = MutableStateFlow<Step?>(null)
    val state: StateFlow<Step?> = _state.asStateFlow()

    internal fun set(step: Step?) {
        _state.value = step
    }
}
