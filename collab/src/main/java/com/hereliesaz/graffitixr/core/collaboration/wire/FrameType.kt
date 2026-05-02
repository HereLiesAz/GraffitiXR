package com.hereliesaz.graffitixr.core.collaboration.wire

internal enum class FrameType(val code: Byte) {
    HELLO(0x10),
    HELLO_OK(0x11),
    HELLO_REJECTED(0x12),
    BULK_BEGIN(0x20),
    BULK_FINGERPRINT(0x21),
    BULK_PROJECT(0x22),
    BULK_END(0x23),
    BULK_ACK(0x24),
    DELTA(0x30),
    DELTA_ACK(0x31),
    PING(0x40),
    PONG(0x41),
    BYE(0x50);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun ofCode(code: Byte): FrameType? = byCode[code]
    }
}
