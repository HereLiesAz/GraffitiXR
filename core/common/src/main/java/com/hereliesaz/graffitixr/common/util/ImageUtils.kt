package com.hereliesaz.graffitixr.common.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Consolidated utility class for [Bitmap] operations.
 *
 * Provides both synchronous and asynchronous methods for:
 * - Loading bitmaps from URIs
 * - Decoding image dimensions without full memory allocation
 * - Transforming bitmaps (rotation)
 * - Saving/caching bitmaps
 * - Format conversion
 *
 * Handles API level differences (ImageDecoder vs MediaStore/BitmapFactory).
 */
object ImageUtils {

    // ==================== Async Operations (Recommended) ====================

    /**
     * Loads a [Bitmap] from the specified URI asynchronously.
     *
     * Handles API level differences: uses ImageDecoder on Android P+,
     * falls back to MediaStore on older versions.
     *
     * @param context Application context.
     * @param uri The URI of the image to load.
     * @return The loaded [Bitmap], or null if loading failed.
     */
    suspend fun loadBitmapAsync(context: Context, uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Retrieves the width and height of an image without loading the full bitmap.
     *
     * @param context Application context.
     * @param uri The URI of the image.
     * @return A [Pair] containing (width, height), or (0, 0) if failed.
     */
    suspend fun getBitmapDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    var width = 0
                    var height = 0
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        width = info.size.width
                        height = info.size.height
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                    }
                    Pair(width, height)
                } else {
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        BitmapFactory.decodeStream(inputStream, null, options)
                    }
                    Pair(options.outWidth, options.outHeight)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Pair(0, 0)
            }
        }
    }

    // ==================== Sync Operations (Use sparingly) ====================

    /**
     * Loads a [Bitmap] from the specified URI synchronously.
     *
     * Prefer [loadBitmapAsync] for main-thread safety.
     * Use this only when you're already on a background thread.
     *
     * @param context Application context.
     * @param uri The URI of the image to load.
     * @return The loaded [Bitmap], or null if loading failed.
     */
    fun loadBitmapSync(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ==================== Bitmap Transformations ====================

    /**
     * Rotates a bitmap by the specified degrees.
     *
     * @param bitmap The source bitmap.
     * @param degrees The angle to rotate (e.g., 90f, -90f).
     * @return A new [Bitmap] instance with the rotation applied.
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ==================== Serialization ====================

    /**
     * Saves a bitmap to the app's cache directory.
     *
     * @param context Application context.
     * @param bitmap The bitmap to save.
     * @param format The compression format (default: PNG).
     * @param quality The compression quality 0-100 (default: 100).
     * @return The [Uri] pointing to the saved file.
     */
    fun saveBitmapToCache(
        context: Context,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ): Uri {
        val extension = when (format) {
            Bitmap.CompressFormat.JPEG -> "jpg"
            Bitmap.CompressFormat.WEBP_LOSSY, Bitmap.CompressFormat.WEBP_LOSSLESS -> "webp"
            else -> "png"
        }
        val filename = "layer_${UUID.randomUUID()}.$extension"
        val file = File(context.cacheDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(format, quality, out)
        }
        return Uri.fromFile(file)
    }

    /**
     * Converts a [Bitmap] to a [ByteArray] in the specified format.
     *
     * @param bitmap The source bitmap.
     * @param format The compression format (default: PNG).
     * @param quality The compression quality 0-100 (default: 100).
     * @return The [ByteArray] representation of the bitmap.
     */
    fun bitmapToByteArray(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(format, quality, stream)
        return stream.toByteArray()
    }

    // ==================== UI Utilities ====================

    /**
     * Cycles through available BlendModes for the UI.
     *
     * @param current The current blend mode name.
     * @return The next blend mode in the cycle.
     */
    fun getNextBlendMode(current: String): String {
        val modes = listOf(
            "SrcOver", "Multiply", "Screen", "Overlay",
            "Darken", "Lighten", "ColorDodge", "ColorBurn",
            "HardLight", "SoftLight", "Difference", "Exclusion",
            "Hue", "Saturation", "Color", "Luminosity"
        )
        val index = modes.indexOf(current)
        return if (index == -1 || index == modes.lastIndex) {
            modes[0]
        } else {
            modes[index + 1]
        }
    }
}
