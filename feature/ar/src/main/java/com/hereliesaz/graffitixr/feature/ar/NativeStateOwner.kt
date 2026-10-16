package com.hereliesaz.graffitixr.feature.ar

/**
 * Which project the process-global native MobileGS state (wall map, paint marks, grid, wall normal)
 * currently belongs to — set only once a loader has finished swapping that state in.
 *
 * `loadedProjectId` flips the moment a different project is emitted, but native keeps the PREVIOUS
 * project's map until the (asynchronous, IO-dispatched) loader has replaced it. An autosave landing in
 * that window passed the "is this the loaded project" check and wrote project A's wall map, paint marks
 * and wall normal into project B. Ownership is therefore a separate token with a strict lifecycle:
 *
 *  - [beginSwitch] when a different project becomes current — the token is dropped BEFORE any native
 *    state is touched, so a save racing the load sees a mismatch and skips;
 *  - [clear] whenever native is wiped without a replacement;
 *  - [markLoaded] as the loader's LAST step, only if that project is still the current one (a slower
 *    loader for a project the user has already left must not claim ownership);
 *  - [owns] is what a save must check.
 *
 * Thread-safe; all methods are cheap.
 */
internal class NativeStateOwner {
    @Volatile private var owner: String? = null

    /** A different project is becoming current: native still holds the previous one's state. */
    @Synchronized
    fun beginSwitch(newProjectId: String) {
        if (owner != newProjectId) owner = null
    }

    @Synchronized
    fun clear() {
        owner = null
    }

    /** The loader for [projectId] finished swapping native state; [stillCurrent] is read AFTER that. */
    @Synchronized
    fun markLoaded(projectId: String, stillCurrent: Boolean) {
        if (stillCurrent) owner = projectId
    }

    fun owns(projectId: String): Boolean = owner == projectId
}
