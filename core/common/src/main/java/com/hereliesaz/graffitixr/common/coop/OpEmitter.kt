package com.hereliesaz.graffitixr.common.coop

import com.hereliesaz.graffitixr.common.model.Op

/**
 * Single point through which editor layer mutations flow into the co-op session.
 * Editor code calls emit(op) unconditionally; impls handle the active-vs-inactive
 * branching internally. NoOpOpEmitter is the default when not hosting.
 */
fun interface OpEmitter {
    fun emit(op: Op)
}
