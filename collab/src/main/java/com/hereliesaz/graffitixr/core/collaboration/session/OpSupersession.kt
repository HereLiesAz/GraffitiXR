package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.Op

/**
 * Which earlier ops [incoming] makes redundant, or null when it subsumes nothing.
 *
 * Shared by the host's replay buffer ([DeltaBuffer]) and the guest's unacknowledged-edit queue
 * ([GuestOutbox]) so both coalesce by the same rules — see [DeltaBuffer]'s supersede doc for why
 * each rule is what it is (absolute-state ops keep only the newest; ModeTransform per mode;
 * DesignReplace never subsumes ModeTransform; strokes are incremental).
 */
internal fun subsumedBy(incoming: Op): ((Op) -> Boolean)? = when (incoming) {
    is Op.DesignReplace -> { op -> op !is Op.ModeTransform }
    is Op.DesignBitmapReplace -> { op -> op is Op.DesignBitmapReplace || op is Op.StrokeComplete }
    is Op.DesignTransform -> { op -> op is Op.DesignTransform }
    is Op.DesignProps -> { op -> op is Op.DesignProps }
    is Op.ModeTransform -> { op -> op is Op.ModeTransform && op.mode == incoming.mode }
    is Op.StrokeComplete, is Op.TextContentChange -> null
}
