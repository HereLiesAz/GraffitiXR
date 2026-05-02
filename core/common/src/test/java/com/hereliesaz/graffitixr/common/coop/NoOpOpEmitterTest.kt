package com.hereliesaz.graffitixr.common.coop

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Op
import org.junit.Test

class NoOpOpEmitterTest {

    @Test
    fun `emit does not throw and returns Unit`() {
        val emitter: OpEmitter = NoOpOpEmitter()
        emitter.emit(Op.LayerAdd(Layer(id = "L1", name = "one")))
    }
}
