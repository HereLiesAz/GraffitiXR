package com.hereliesaz.graffitixr.data.azphalt

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal blocking HTTP transport for [RepositoryClient], built on [HttpURLConnection] so the data
 * module keeps its no-Retrofit footprint. Both calls throw on a non-2xx response (or any transport
 * error), so a registry outage surfaces as an exception the caller can fall back on rather than a
 * silently-empty catalog. Blocking IO — call off the main thread.
 */
internal object HttpTransport {
    private const val TIMEOUT_MS = 15_000

    fun get(url: String, headers: Map<String, String>): String = request("GET", url, headers, null)

    fun post(url: String, headers: Map<String, String>, body: String): String =
        request("POST", url, headers, body)

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("HTTP $code from $url: ${err.take(200)}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
