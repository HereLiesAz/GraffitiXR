package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Texture {
    var width = 0
    var height = 0
    var channels = 4
    var data: ByteBuffer? = null

    companion object {
        @JvmStatic
        fun loadTextureFromUri(context: Context, uri: Uri): Texture? {
            return try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bufferedStream = BufferedInputStream(inputStream)
                val bitMap = BitmapFactory.decodeStream(bufferedStream)
                val data = IntArray(bitMap.width * bitMap.height)
                bitMap.getPixels(
                    data, 0, bitMap.width, 0, 0,
                    bitMap.width, bitMap.height
                )
                loadTextureFromIntBuffer(
                    data, bitMap.width,
                    bitMap.height
                )
            } catch (e: IOException) {
                Log.e("Texture", "Failed to load texture from URI: $uri", e)
                null
            }
        }

        @JvmStatic
        fun loadTextureFromApk(
            fileName: String,
            assets: AssetManager
        ): Texture? {
            val inputStream: InputStream
            return try {
                inputStream = assets.open(fileName, AssetManager.ACCESS_BUFFER)
                val bufferedStream = BufferedInputStream(
                    inputStream
                )
                val bitMap = BitmapFactory.decodeStream(bufferedStream)
                val data = IntArray(bitMap.width * bitMap.height)
                bitMap.getPixels(
                    data, 0, bitMap.width, 0, 0,
                    bitMap.width, bitMap.height
                )
                loadTextureFromIntBuffer(
                    data, bitMap.width,
                    bitMap.height
                )
            } catch (e: IOException) {
                Log.e("Texture", "Failed to load texture '$fileName' from APK")
                Log.d("Texture", e.message.toString())
                null
            }
        }

        @JvmStatic
        private fun loadTextureFromIntBuffer(
            data: IntArray, width: Int,
            height: Int
        ): Texture? {
            val numPixels = width * height
            val dataBytes = ByteArray(numPixels * 4)
            for (p in 0 until numPixels) {
                val colour = data[p]
                dataBytes[p * 4] = (colour ushr 16).toByte() // R
                dataBytes[p * 4 + 1] = (colour ushr 8).toByte() // G
                dataBytes[p * 4 + 2] = colour.toByte() // B
                dataBytes[p * 4 + 3] = (colour ushr 24).toByte() // A
            }
            val texture: Texture = Texture()
            texture.width = width
            texture.height = height
            texture.data = ByteBuffer.allocateDirect(dataBytes.size).order(
                ByteOrder.nativeOrder()
            )
            val rowSize: Int = texture.width * texture.channels
            for (r in 0 until texture.height) texture.data?.put(
                dataBytes, rowSize * (texture.height - 1 - r),
                rowSize
            )
            texture.data?.rewind()
            return texture
        }
    }
}
