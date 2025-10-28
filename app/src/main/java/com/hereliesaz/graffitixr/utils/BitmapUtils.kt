package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object BitmapUtils {

    suspend fun getBitmapDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        return withContext(Dispatchers.IO) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                var width = 0
                var height = 0
                ImageDecoder.decodeBitmap(
                    source,
                    object : ImageDecoder.OnHeaderDecodedListener {
                        override fun onHeaderDecoded(
                            decoder: ImageDecoder,
                            info: ImageDecoder.ImageInfo,
                            source: ImageDecoder.Source
                        ) {
                            width = info.size.width
                            height = info.size.height
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                        }
                    }
                )
                Pair(width, height)
            } catch (e: IOException) {
                e.printStackTrace()
                Pair(0, 0)
            }
        }
    }

    suspend fun getBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } catch (e: IOException) {
                e.printStackTrace()
                null
            }
        }
    }
}
