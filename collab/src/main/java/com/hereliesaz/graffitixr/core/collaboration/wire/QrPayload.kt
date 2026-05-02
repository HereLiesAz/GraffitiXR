package com.hereliesaz.graffitixr.core.collaboration.wire

import android.net.Uri
import java.security.SecureRandom
import java.util.Base64

/**
 * QR-pairing payload format:
 *   gxr://coop?h=<host-ip>&p=<port>&t=<token>&v=<protocolVersion>
 */
internal data class QrPayload(
    val host: String,
    val port: Int,
    val token: String,
    val protocolVersion: Int,
) {
    fun encode(): String {
        require(port in 1..65535) { "port out of range: $port" }
        require(token.isNotBlank()) { "token must not be blank" }
        require(protocolVersion >= 0) { "protocolVersion must be non-negative" }
        return "gxr://coop?h=$host&p=$port&t=$token&v=$protocolVersion"
    }

    companion object {
        const val SCHEME = "gxr"
        const val HOST_KEYWORD = "coop"
        private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()

        fun parse(input: String): QrPayload {
            val uri = try {
                Uri.parse(input)
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid URI: $input", e)
            }
            require(uri.scheme == SCHEME) { "wrong scheme '${uri.scheme}', expected $SCHEME" }
            require(uri.host == HOST_KEYWORD) { "wrong host '${uri.host}', expected $HOST_KEYWORD" }
            val host = uri.getQueryParameter("h") ?: error("missing 'h'")
            val port = uri.getQueryParameter("p")?.toIntOrNull()
                ?: error("missing or invalid 'p'")
            require(port in 1..65535) { "port out of range: $port" }
            val token = uri.getQueryParameter("t") ?: error("missing 't'")
            require(token.isNotBlank()) { "blank token" }
            require(token.length <= 256) { "oversized token" }
            val v = uri.getQueryParameter("v")?.toIntOrNull()
                ?: error("missing or invalid 'v'")
            return QrPayload(host = host, port = port, token = token, protocolVersion = v)
        }

        /** Generate a 128-bit base64url token. */
        fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return base64UrlEncoder.encodeToString(bytes)
        }
    }
}
