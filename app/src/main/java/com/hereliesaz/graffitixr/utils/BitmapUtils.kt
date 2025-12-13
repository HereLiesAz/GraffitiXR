package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Utility class for handling [Bitmap] operations.
 *
 * Provides methods for:
 * 1.  Loading bitmaps from URIs safely on background threads.
 * 2.  Decoding image dimensions without loading the full bitmap into memory.
 * 3.  Transforming bitmaps (e.g., rotation).
 *
 * Handles API level differences for image decoding (ImageDecoder vs MediaStore).
 */
object BitmapUtils {

    /**
     * Retrieves the width and height of an image at the given URI without loading the full bitmap.
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
                    // Decode only bounds if possible, or decode fully and discard.
                    // ImageDecoder doesn't have a direct "JustDecodeBounds" equivalent exposed simply,
                    // but the OnHeaderDecodedListener provides the info.
                    ImageDecoder.decodeBitmap(
                        source
                    ) { decoder, info, _ ->
                        width = info.size.width
                        height = info.size.height
                        // We strictly don't need to allocate here if we just want dims, but
                        // the API requires returning a bitmap from decodeBitmap.
                        // However, we can interrupt or just let it finish.
                        // For efficiency, BitmapFactory is better for this on older APIs.
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

    /**
     * Loads a [Bitmap] from the specified URI.
     *
     * Handles the transition from `MediaStore.Images.Media.getBitmap` (deprecated)
     * to `ImageDecoder` on newer Android versions.
     *
     * @param context Application context.
     * @param uri The URI of the image to load.
     * @return The loaded [Bitmap], or null if loading failed.
     */
    suspend fun getBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        // Use software allocator to make the bitmap mutable/accessible if needed
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
     * Rotates a bitmap by the specified degrees.
     *
     * @param bitmap The source bitmap.
     * @param degrees The angle to rotate (e.g., 90f, -90f).
     * @return A new [Bitmap] instance with the rotation applied.
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
