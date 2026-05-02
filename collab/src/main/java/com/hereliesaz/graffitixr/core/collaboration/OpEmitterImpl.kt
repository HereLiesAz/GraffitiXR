package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.common.model.Op
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpEmitterImpl @Inject constructor(
    private val collaborationManager: CollaborationManager,
) : OpEmitter {
    override fun emit(op: Op) {
        collaborationManager.enqueueHostOp(op)
    }
}
