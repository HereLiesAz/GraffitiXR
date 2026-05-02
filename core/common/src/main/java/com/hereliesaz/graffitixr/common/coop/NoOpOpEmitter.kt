package com.hereliesaz.graffitixr.common.coop

import com.hereliesaz.graffitixr.common.model.Op
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoOpOpEmitter @Inject constructor() : OpEmitter {
    override fun emit(op: Op) { /* drop */ }
}
