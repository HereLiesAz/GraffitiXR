package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.common.model.Op

/**
 * Which guest edits the host may apply (protocol v4). Enforced on the host (authoritative) and
 * mirrored on the guest so a refused edit is reported locally instead of sent and silently dropped.
 *
 * Refused:
 *  - [Op.DesignReplace]: it carries a [com.hereliesaz.graffitixr.common.model.Layer] whose `uri`
 *    names a file on the GUEST (its spectator copy). The host would persist that path into its own
 *    project — a dangling asset that then fails every later bulk/export, and, from a hostile guest,
 *    a path of its choosing the host would later load and re-broadcast. Choosing the design image
 *    stays a host action.
 *  - [Op.DesignBitmapReplace]: the host persists a design as its source image plus effect flags
 *    (Outline, subject isolation) and re-derives the pixels on load; a guest's rendered pixels carry
 *    neither, so applying them would last only until the host's next reload or bulk. A guest's
 *    effect toggle stays on the guest. (It also pairs with a refused DesignReplace on import.)
 *  - an AR [Op.ModeTransform] between peers on DIFFERENT tracking backends. The AR placement is
 *    expressed in each backend's own wall frame (ARCore anchor vs standalone centred KPM page); the
 *    bulk transfer normalises it once (`CoopSpectatorProjectNormalizer`), but a live op carries no
 *    conversion, so applying it verbatim would move the host's artwork to the wrong place.
 *  - [Op.StrokeComplete] / [Op.TextContentChange]: authoring ops this app neither emits nor
 *    applies; accepting them would only ship bytes the host discards.
 *
 * [Op.WallWidth] is accepted only when it is a plausible width (finite, within
 * (0, [MAX_WALL_WIDTH_M]]), the same bound the Measure tool itself enforces; it is a metric length,
 * so unlike an AR placement it is valid between any two backends or devices.
 *
 * Everything else is design state or a non-AR mode's screen-space adjustment, which mean the same
 * thing on both devices.
 */
internal object GuestOpPolicy {
    fun allows(op: Op, hostBackend: CoopTrackingBackend?, guestBackend: CoopTrackingBackend?): Boolean = when (op) {
        is Op.ModeTransform ->
            op.mode != AR_MODE || (hostBackend != null && hostBackend == guestBackend)
        is Op.StrokeComplete, is Op.TextContentChange, is Op.DesignReplace, is Op.DesignBitmapReplace -> false
        is Op.WallWidth -> op.meters.isFinite() && op.meters > 0f && op.meters <= MAX_WALL_WIDTH_M
        is Op.DesignTransform, is Op.DesignProps -> true
    }

    /** `WallMeasure.MAX_WIDTH_M`; the collab module does not depend on the AR feature. */
    const val MAX_WALL_WIDTH_M = 100f

    /** `EditorMode.AR.name`; the collab module does not depend on the editor's enum. */
    const val AR_MODE = "AR"
}
