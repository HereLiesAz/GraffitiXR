package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.LayerProps
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.common.model.Op
import org.junit.Assert.assertEquals
import org.junit.Test

class GuestOutboxTest {

    private fun props(o: Float) = Op.DesignProps(LayerProps(opacity = o))

    @Test
    fun `sequence numbers start at 1 and increase`() {
        val box = GuestOutbox()
        assertEquals(1L, box.add(props(0.1f)))
        assertEquals(2L, box.add(Op.ModeTransform("TRACE", ModeAdjustment())))
        assertEquals(listOf(1L, 2L), box.pendingAfter(0).map { it.first })
    }

    @Test
    fun `a newer absolute op supersedes the queued one of the same kind`() {
        val box = GuestOutbox()
        box.add(props(0.1f))
        box.add(Op.ModeTransform("AR", ModeAdjustment(scale = 2f)))
        box.add(props(0.9f))
        val pending = box.pendingAfter(0)
        assertEquals(2, pending.size)
        assertEquals(2L, pending[0].first)              // the ModeTransform survives
        assertEquals(0.9f, (pending[1].second as Op.DesignProps).props.opacity, 0f)
    }

    @Test
    fun `mode transforms only supersede their own mode`() {
        val box = GuestOutbox()
        box.add(Op.ModeTransform("AR", ModeAdjustment()))
        box.add(Op.ModeTransform("TRACE", ModeAdjustment()))
        assertEquals(2, box.size())
    }

    @Test
    fun `ack drops everything up to and including its seq`() {
        val box = GuestOutbox()
        box.add(Op.ModeTransform("AR", ModeAdjustment()))
        box.add(Op.ModeTransform("TRACE", ModeAdjustment()))
        box.add(Op.ModeTransform("MOCKUP", ModeAdjustment()))
        box.ackUpTo(2)
        assertEquals(listOf(3L), box.pendingAfter(0).map { it.first })
    }

    @Test
    fun `pendingAfter skips what this connection already sent`() {
        val box = GuestOutbox()
        box.add(Op.ModeTransform("AR", ModeAdjustment()))
        box.add(Op.ModeTransform("TRACE", ModeAdjustment()))
        assertEquals(listOf(2L), box.pendingAfter(1).map { it.first })
    }

    @Test
    fun `overflow evicts the oldest and counts it`() {
        val box = GuestOutbox(maxOps = 2)
        box.add(Op.ModeTransform("AR", ModeAdjustment()))
        box.add(Op.ModeTransform("TRACE", ModeAdjustment()))
        box.add(Op.ModeTransform("MOCKUP", ModeAdjustment()))
        assertEquals(listOf(2L, 3L), box.pendingAfter(0).map { it.first })
        assertEquals(1, box.evicted)
    }
}
