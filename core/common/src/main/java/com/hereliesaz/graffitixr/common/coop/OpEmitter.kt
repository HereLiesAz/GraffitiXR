package com.hereliesaz.graffitixr.common.coop

import com.hereliesaz.graffitixr.common.model.Op

/**
 * Single point through which editor layer mutations flow into the co-op session.
 * Editor code calls emit(op) unconditionally; impls handle the active-vs-inactive
 * branching internally. NoOpOpEmitter is the default when not hosting.
 */
fun interface OpEmitter {
    fun emit(op: Op)

    /**
     * Whether an emitted op can reach anyone. Callers check it before building an expensive op —
     * a design's PNG bytes run to megabytes and were encoded on the main thread on every effect
     * change, solo or not, until the heap ran out. Defaults to true so a lambda emitter still sees
     * every op.
     */
    fun hasSession(): Boolean = true
}
