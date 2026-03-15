package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Typeface
import android.util.LruCache
import androidx.core.provider.FontRequest
import androidx.core.provider.FontsContractCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hereliesaz.graffitixr.feature.editor.R

object GoogleFontCache {

    private val cache = LruCache<String, Typeface>(32)

    suspend fun getTypeface(
        context: Context,
        fontName: String,
        bold: Boolean,
        italic: Boolean
    ): Typeface = withContext(Dispatchers.IO) {
        val key = "$fontName|$bold|$italic"
        cache.get(key)?.let { return@withContext it }

        val query = "name=$fontName&weight=400&italic=0&besteffort=true"
        val request = FontRequest(
            "com.google.android.gms.fonts",
            "com.google.android.gms",
            query,
            R.array.com_google_android_gms_fonts_certs
        )

        return@withContext try {
            val result = FontsContractCompat.fetchFonts(context, null, request)
            val typeface = if (result.statusCode == FontsContractCompat.FontFamilyResult.STATUS_OK) {
                FontsContractCompat.buildTypeface(context, null, result.fonts) ?: Typeface.DEFAULT
            } else {
                Typeface.DEFAULT
            }
            val styled = when {
                bold && italic -> Typeface.create(typeface, Typeface.BOLD_ITALIC)
                bold -> Typeface.create(typeface, Typeface.BOLD)
                italic -> Typeface.create(typeface, Typeface.ITALIC)
                else -> typeface
            }
            cache.put(key, styled)
            styled
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
    }
}
