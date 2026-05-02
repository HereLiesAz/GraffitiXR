package com.hereliesaz.graffitixr.core.collaboration.session

internal enum class Phase {
    Handshake,
    Bulk,
    Live,
    Reconnecting,
    Ended,
}
