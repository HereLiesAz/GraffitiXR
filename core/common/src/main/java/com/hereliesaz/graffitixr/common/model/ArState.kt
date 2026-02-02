package com.hereliesaz.graffitixr.common.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents the different states of the AR interaction lifecycle.
 * This sealed class provides a more robust and explicit state machine
 * than a simple boolean flag.
 */
@Parcelize
sealed class ArState : Parcelable {
    /**
     * The initial state where the application is actively searching for AR planes
     * on which to place content.
     */
    @Parcelize
    data object SEARCHING : ArState()

    /**
     * The state after an object has been placed on a detected plane. In this state,
     * the user can manipulate the object (scale, rotate).
     */
    @Parcelize
    data object PLACED : ArState()

    /**
     * The final state where the object's position and orientation are locked.
     * In this state, the application may perform additional processing, like
     * detecting and storing feature points for persistence.
     */
    @Parcelize
    data object LOCKED : ArState()
}