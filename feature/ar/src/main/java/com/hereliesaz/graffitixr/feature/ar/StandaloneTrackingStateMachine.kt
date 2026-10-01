package com.hereliesaz.graffitixr.feature.ar

enum class StandaloneTrackingState {
    INITIALIZING,
    LOCKED,
    IMU_BRIDGE,
    REACQUIRING,
    LOST,
    FATAL,
}

data class StandaloneTrackingStateConfig(
    val confirmationFrames: Int = 2,
    val lostAfterMs: Long = 2_000L,
) {
    init {
        require(confirmationFrames >= 1)
        require(lostAfterMs >= 0L)
    }
}

/**
 * Pure tracking-state hysteresis for the standalone backend.
 *
 * Initial acquisition and post-loss reacquisition require consecutive good visual poses. A brief
 * miss from a locked state becomes IMU_BRIDGE rather than LOST; once the bridge is unavailable we
 * enter REACQUIRING and only call the state LOST after [StandaloneTrackingStateConfig.lostAfterMs].
 */
internal class StandaloneTrackingStateMachine(
    private val config: StandaloneTrackingStateConfig = StandaloneTrackingStateConfig(),
) {
    var state: StandaloneTrackingState = StandaloneTrackingState.INITIALIZING
        private set

    private var everLocked = false
    private var consecutiveAccepted = 0
    private var reacquireStartedMs: Long? = null

    fun onAcceptedVisual(nowMs: Long): StandaloneTrackingState {
        if (state == StandaloneTrackingState.FATAL) return state

        when (state) {
            StandaloneTrackingState.LOCKED,
            StandaloneTrackingState.IMU_BRIDGE -> {
                consecutiveAccepted = config.confirmationFrames
                everLocked = true
                reacquireStartedMs = null
                state = StandaloneTrackingState.LOCKED
            }

            StandaloneTrackingState.INITIALIZING,
            StandaloneTrackingState.REACQUIRING,
            StandaloneTrackingState.LOST -> {
                consecutiveAccepted++
                if (consecutiveAccepted >= config.confirmationFrames) {
                    everLocked = true
                    reacquireStartedMs = null
                    state = StandaloneTrackingState.LOCKED
                } else if (everLocked) {
                    if (reacquireStartedMs == null) reacquireStartedMs = nowMs
                    state = StandaloneTrackingState.REACQUIRING
                } else {
                    state = StandaloneTrackingState.INITIALIZING
                }
            }

            StandaloneTrackingState.FATAL -> Unit
        }
        return state
    }

    fun onVisualMiss(bridgeAvailable: Boolean, nowMs: Long): StandaloneTrackingState {
        if (state == StandaloneTrackingState.FATAL) return state
        consecutiveAccepted = 0

        if (!everLocked) {
            state = StandaloneTrackingState.INITIALIZING
            return state
        }
        if (bridgeAvailable) {
            state = StandaloneTrackingState.IMU_BRIDGE
            return state
        }

        val started = reacquireStartedMs ?: nowMs.also { reacquireStartedMs = it }
        state = if (nowMs - started >= config.lostAfterMs) {
            StandaloneTrackingState.LOST
        } else {
            StandaloneTrackingState.REACQUIRING
        }
        return state
    }

    fun onFatal(): StandaloneTrackingState {
        state = StandaloneTrackingState.FATAL
        consecutiveAccepted = 0
        return state
    }

    fun reset(): StandaloneTrackingState {
        state = StandaloneTrackingState.INITIALIZING
        everLocked = false
        consecutiveAccepted = 0
        reacquireStartedMs = null
        return state
    }
}
