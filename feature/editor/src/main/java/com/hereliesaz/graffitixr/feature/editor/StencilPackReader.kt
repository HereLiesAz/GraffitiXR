package com.hereliesaz.graffitixr.feature.editor

import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A single Stencil Artist-authored sheet; never a request to run stencil generation. */
internal data class StencilPackSheet(val name: String, val color: Long, val visible: Boolean, val png: ByteArray)

/** Versioned StencilPack v1, in paint order. */
internal data class StencilPack(val width: Int, val height: Int, val sheets: List<StencilPackSheet>)

/** Android receiver for the public StencilPack v1 format. Decompression and images are bounded. */
internal object StencilPackReader {
    private const val MAX_ARCHIVE_BYTES = 48L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_PIXELS = 24_000_000L

    fun read(input: InputStream): StencilPack? {
        return try {
            val files = LinkedHashMap<String, ByteArray>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || name.isBlank() || name.startsWith("/") ||
                        name.contains("..") || name.contains('\\') || name.contains('/') ||
                        files.containsKey(name) || files.size >= 1026
                    ) return null
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        if (bytes.size() + read > MAX_ENTRY_BYTES || total > MAX_ARCHIVE_BYTES) return null
                        bytes.write(buffer, 0, read)
                    }
                    files[name] = bytes.toByteArray()
                }
            }
            val manifest = files["manifest.json"] ?: return null
            if (manifest.size > 256 * 1024) return null
            val json = JSONObject(manifest.toString(Charsets.UTF_8))
            if (json.optString("format") != "com.hereliesaz.stencilpack" ||
                json.optInt("version") != 1 || json.optString("order") != "bottom-to-top" ||
                json.optString("coverage") != "overpaint"
            ) return null
            val width = json.optInt("width")
            val height = json.optInt("height")
            val layers = json.optJSONArray("layers") ?: return null
            if (width !in 1..8192 || height !in 1..8192 ||
                layers.length() !in 1..1024 ||
                width.toLong() * height.toLong() * layers.length() > MAX_TOTAL_PIXELS
            ) return null
            val result = ArrayList<StencilPackSheet>(layers.length())
            val used = HashSet<String>()
            for (i in 0 until layers.length()) {
                val entry = layers.getJSONObject(i)
                val file = entry.optString("file")
                if (!file.matches(Regex("stencil-sheet-[0-9]+[.]png")) || !used.add(file)) return null
                val png = files[file] ?: return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
                if (bounds.outWidth != width || bounds.outHeight != height) return null
                val colorHex = entry.optString("color").removePrefix("#")
                if (!colorHex.matches(Regex("[a-fA-F0-9]{8}"))) return null
                result += StencilPackSheet(
                    name = entry.optString("name").ifBlank { "Sheet ${i + 1}" }.take(128),
                    color = colorHex.toLong(16),
                    visible = entry.optBoolean("visible", true),
                    png = png,
                )
            }
            StencilPack(width, height, result)
        } catch (_: Exception) {
            null
        }
    }
}
