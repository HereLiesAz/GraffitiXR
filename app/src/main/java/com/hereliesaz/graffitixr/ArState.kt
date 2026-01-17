package com.hereliesaz.graffitixr

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents the different states of the AR interaction lifecycle.
 */
@Parcelize
enum class ArState : Parcelable {
    /**
     * The initial state where the application is actively searching for AR planes
     * on which to place content.
     */
    SEARCHING,

    /**
     * The state after an object has been placed on a detected plane. In this state,
     * the user can manipulate the object (scale, rotate).
     */
    PLACED,

    /**
     * The final state where the object's position and orientation are locked.
     */
    LOCKED
}
