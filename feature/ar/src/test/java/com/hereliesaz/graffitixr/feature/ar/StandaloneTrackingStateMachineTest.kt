package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Test

class StandaloneTrackingStateMachineTest {

    @Test
    fun initialAcquisitionRequiresTwoGoodFrames() {
        val state = StandaloneTrackingStateMachine()
        assertEquals(StandaloneTrackingState.INITIALIZING, state.onAcceptedVisual(0))
        assertEquals(StandaloneTrackingState.LOCKED, state.onAcceptedVisual(10))
    }

    @Test
    fun shortMissUsesBridgeWithoutFlappingToLost() {
        val state = StandaloneTrackingStateMachine()
        state.onAcceptedVisual(0)
        state.onAcceptedVisual(10)

        assertEquals(StandaloneTrackingState.IMU_BRIDGE, state.onVisualMiss(true, 20))
        assertEquals(StandaloneTrackingState.LOCKED, state.onAcceptedVisual(30))
    }

    @Test
    fun expiredBridgeMovesThroughReacquiringBeforeLost() {
        val state = StandaloneTrackingStateMachine(
            StandaloneTrackingStateConfig(confirmationFrames = 2, lostAfterMs = 2_000),
        )
        state.onAcceptedVisual(0)
        state.onAcceptedVisual(10)

        assertEquals(StandaloneTrackingState.REACQUIRING, state.onVisualMiss(false, 100))
        assertEquals(StandaloneTrackingState.REACQUIRING, state.onVisualMiss(false, 2_099))
        assertEquals(StandaloneTrackingState.LOST, state.onVisualMiss(false, 2_100))
    }

    @Test
    fun reacquisitionRequiresConsecutiveGoodFrames() {
        val state = StandaloneTrackingStateMachine(
            StandaloneTrackingStateConfig(confirmationFrames = 2, lostAfterMs = 0),
        )
        state.onAcceptedVisual(0)
        state.onAcceptedVisual(10)
        state.onVisualMiss(false, 20)

        assertEquals(StandaloneTrackingState.REACQUIRING, state.onAcceptedVisual(30))
        assertEquals(StandaloneTrackingState.LOCKED, state.onAcceptedVisual(40))
    }

    @Test
    fun fatalIsStickyUntilReset() {
        val state = StandaloneTrackingStateMachine()
        assertEquals(StandaloneTrackingState.FATAL, state.onFatal())
        assertEquals(StandaloneTrackingState.FATAL, state.onAcceptedVisual(1))
        assertEquals(StandaloneTrackingState.FATAL, state.onVisualMiss(true, 2))
        assertEquals(StandaloneTrackingState.INITIALIZING, state.reset())
    }
}
