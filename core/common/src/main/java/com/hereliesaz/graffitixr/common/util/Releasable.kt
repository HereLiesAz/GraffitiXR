package com.hereliesaz.graffitixr.common.util

/**
 * Contract for objects that own OpenGL resources (shader programs, textures, buffers).
 *
 * Implementations must delete every GL object they allocated, and must be idempotent:
 * calling [release] more than once, or before any GL object was created, has to be a no-op.
 *
 * GL objects may only be deleted on the thread that owns the GL context. Callers are
 * responsible for invoking [release] on the GL thread (e.g. via `GLSurfaceView.queueEvent`),
 * never directly from a ViewModel coroutine.
 */
interface GlReleasable {
    fun release()
}
