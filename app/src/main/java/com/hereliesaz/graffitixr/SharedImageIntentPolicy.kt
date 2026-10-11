package com.hereliesaz.graffitixr

import android.content.Intent

/**
 * Whether MainActivity should read a shared-in image (ACTION_SEND / image ACTION_VIEW) from the
 * intent it was handed.
 *
 * The launch intent outlives the launch: it is handed back unchanged when the activity is recreated
 * (a locale switch, a process restore) and when the task is relaunched from Recents. Reading it in
 * each of those added the same image as a new layer again. Only a fresh delivery counts.
 */
internal object SharedImageIntentPolicy {

    /**
     * @param isRestoredInstance true when the activity is being recreated from saved state.
     * @param launchFlags the intent's flags.
     */
    fun shouldConsume(isRestoredInstance: Boolean, launchFlags: Int): Boolean {
        if (isRestoredInstance) return false
        return launchFlags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
    }
}
