package com.hereliesaz.graffitixr.feature.editor.stencil

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.hereliesaz.graffitixr.common.model.StencilLayer
import com.hereliesaz.graffitixr.common.model.StencilPrintDimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.ceil

/**
 * Generates tiled US Letter PDFs and PNG exports for Stencil Mode.
 *
 * PDF spec:
 *   - Paper:     US Letter 8.5×11in @ 300 DPI = 2550×3300 px
 *   - Printable: 2400×3150 px (0.25in margins)
 *   - Label strip at bottom: 150px reserved per page
 *   - Tile content area: 2400×3000 px
 *   - Overlap:   36 px (≈3mm @ 300 DPI) on right and bottom edges
 *   - Rendering: outline-only via OpenCV findContours, 1pt stroke (≈4px @ 300 DPI)
 *   - Labels:    Layer name, ROW/COL, total grid, registration mark callout
 *   - Divider pages between layers (plain text, white background)
 */
class StencilPrintEngine @Inject constructor() {

    companion object {
        private const val DPI = 300f
        private const val MM_TO_PX = DPI / 25.4f
        
        private const val PAGE_WIDTH = 2550   // 8.5"
        private const val PAGE_HEIGHT = 3300  // 11"
        private const val MARGIN = 75         // 0.25"
        private const val LABEL_ZONE = 150    // reserved for text at bottom
        
        private const val TILE_W = PAGE_WIDTH - 2 * MARGIN
        private const val TILE_H = PAGE_HEIGHT - 2 * MARGIN - LABEL_ZONE
        private const val OVERLAP = 36        // ≈ 3mm
        
        private const val STRIDE_H = TILE_W - OVERLAP
        private const val STRIDE_V = TILE_H - OVERLAP
    }

    /**
     * Generates a multi-layer tiled PDF and returns a content Uri for sharing.
     */
    suspend fun generatePdf(
        context: Context,
        layers: List<StencilLayer>,
        printSizeMm: Float,
        printDimension: StencilPrintDimension
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()
        
        try {
            val reference = layers.firstOrNull() ?: return@withContext Result.failure(Exception("No layers to print"))
            val sourceW = reference.bitmap.width.toFloat()
            val sourceH = reference.bitmap.height.toFloat()
            val aspect = sourceW / sourceH
            
            val outputWidthPx: Float
            val outputHeightPx: Float
            if (printDimension == StencilPrintDimension.WIDTH) {
                outputWidthPx = printSizeMm * MM_TO_PX
                outputHeightPx = outputWidthPx / aspect
            } else {
                outputHeightPx = printSizeMm * MM_TO_PX
                outputWidthPx = outputHeightPx * aspect
            }
            
            val cols = ceil(outputWidthPx / STRIDE_H).toInt().coerceAtLeast(1)
            val rows = ceil(outputHeightPx / STRIDE_V).toInt().coerceAtLeast(1)

            val corners = computeCorners(reference.bitmap)
            val invScale = sourceW / outputWidthPx

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 28f
                typeface = Typeface.MONOSPACE
                isFakeBoldText = true
            }
            
            layers.forEach { layer ->
                // ── Divider Page ──────────────────────────────────────────────────
                val divInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pdfDocument.pages.size).create()
                val divPage = pdfDocument.startPage(divInfo)
                val divCanvas = divPage.canvas
                divCanvas.drawColor(Color.WHITE)
                textPaint.textAlign = Paint.Align.CENTER
                divCanvas.drawText("CUT HERE — START OF ${layer.label.uppercase()}", (PAGE_WIDTH / 2).toFloat(), (PAGE_HEIGHT / 2).toFloat(), textPaint)
                pdfDocument.finishPage(divPage)
                
                // ── Tiled Pages ──────────────────────────────────────────────────
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pdfDocument.pages.size).create()
                        val page = pdfDocument.startPage(pageInfo)
                        val canvas = page.canvas
                        canvas.drawColor(Color.WHITE)
                        
                        // Extract tile content and render as outlines
                        renderTile(canvas, layer.bitmap, row, col, sourceW, sourceH, outputWidthPx, outputHeightPx)
                        
                        // Registration mark callouts
                        val srcX = (col * STRIDE_H * invScale).toInt()
                        val srcY = (row * STRIDE_V * invScale).toInt()
                        val srcW = (TILE_W * invScale).toInt()
                        val srcH = (TILE_H * invScale).toInt()
                        val tileRect = Rect(srcX, srcY, srcX + srcW, srcY + srcH)
                        
                        corners?.filter { pt -> tileRect.contains(pt.x.toInt(), pt.y.toInt()) }?.let { marks ->
                            if (marks.isNotEmpty()) {
                                drawMarkCallouts(canvas, marks, srcX, srcY, invScale)
                            }
                        }

                        // Draw guide lines for overlap
                        drawOverlapGuides(canvas, row < rows - 1, col < cols - 1)
                        
                        // Footer labels
                        textPaint.textAlign = Paint.Align.LEFT
                        val footerY = (PAGE_HEIGHT - MARGIN - 60).toFloat()
                        canvas.drawText("${layer.label.uppercase()}  |  ROW ${row + 1} COL ${col + 1}  |  Page ${row * cols + col + 1} of ${rows * cols}", MARGIN.toFloat(), footerY, textPaint)
                        
                        pdfDocument.finishPage(page)
                    }
                }
            }
            
            val file = File(context.cacheDir, "GraffitiXR_Stencil_${System.currentTimeMillis()}.pdf")
            pdfDocument.writeTo(FileOutputStream(file))
            pdfDocument.close()
            
            val authority = "${context.packageName}.fileprovider"
            Result.success(FileProvider.getUriForFile(context, authority, file))
        } catch (e: Exception) {
            pdfDocument.close()
            Result.failure(e)
        }
    }

    private fun renderTile(
        canvas: Canvas,
        source: Bitmap,
        row: Int,
        col: Int,
        sourceW: Float,
        sourceH: Float,
        outputW: Float,
        outputH: Float
    ) {
        // Create a tile-sized buffer for OpenCV analysis
        val tileBmp = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
        val tileCanvas = Canvas(tileBmp)
        tileCanvas.drawColor(Color.WHITE)
        
        val srcX = (col * STRIDE_H * (sourceW / outputW)).toInt()
        val srcY = (row * STRIDE_V * (sourceH / outputH)).toInt()
        val srcW = (TILE_W * (sourceW / outputW)).toInt()
        val srcH = (TILE_H * (sourceH / outputH)).toInt()
        
        val srcRect = Rect(srcX, srcY, srcX + srcW, srcY + srcH)
        val dstRect = Rect(0, 0, TILE_W, TILE_H)
        
        tileCanvas.drawBitmap(source, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
        
        // Convert to OpenCV and find contours
        val mat = Mat()
        Utils.bitmapToMat(tileBmp, mat)
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        
        // Invert: content is black on white, OpenCV findContours expects white on black
        val binary = Mat()
        Core.bitwise_not(gray, binary)
        
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        
        val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 4f // ≈ 1pt
        }
        
        canvas.save()
        canvas.translate(MARGIN.toFloat(), MARGIN.toFloat())
        
        for (contour in contours) {
            val path = Path()
            val points = contour.toArray()
            if (points.isNotEmpty()) {
                path.moveTo(points[0].x.toFloat(), points[0].y.toFloat())
                for (i in 1 until points.size) {
                    path.lineTo(points[i].x.toFloat(), points[i].y.toFloat())
                }
                path.close()
                canvas.drawPath(path, pathPaint)
            }
        }
        canvas.restore()
        
        // Cleanup
        tileBmp.recycle()
        mat.release(); gray.release(); binary.release(); hierarchy.release()
        contours.forEach { it.release() }
    }

    private fun drawOverlapGuides(canvas: Canvas, hasBottom: Boolean, hasRight: Boolean) {
        val guidePaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 2f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
        
        if (hasRight) {
            val x = (MARGIN + TILE_W - OVERLAP).toFloat()
            canvas.drawLine(x, MARGIN.toFloat(), x, (MARGIN + TILE_H).toFloat(), guidePaint)
        }
        if (hasBottom) {
            val y = (MARGIN + TILE_H - OVERLAP).toFloat()
            canvas.drawLine(MARGIN.toFloat(), y, (MARGIN + TILE_W).toFloat(), y, guidePaint)
        }
    }

    private fun computeCorners(bmp: Bitmap): List<Point>? {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w; var maxX = 0; var minY = h; var maxY = 0
        var found = false
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (Color.red(pixels[y * w + x]) < 128) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                    found = true
                }
            }
        }
        if (!found) return null

        val margin = 20
        val arm = 40

        val l = (minX - margin).coerceAtLeast(arm)
        val t = (minY - margin).coerceAtLeast(arm)
        val r = (maxX + margin).coerceAtMost(w - arm - 1)
        val b = (maxY + margin).coerceAtMost(h - arm - 1)

        return listOf(
            Point(l.toDouble(), t.toDouble()),
            Point(r.toDouble(), t.toDouble()),
            Point(r.toDouble(), b.toDouble()),
            Point(l.toDouble(), b.toDouble())
        )
    }

    private fun drawMarkCallouts(canvas: Canvas, corners: List<Point>, srcX: Int, srcY: Int, invScale: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 24f
            typeface = Typeface.MONOSPACE
        }
        canvas.save()
        canvas.translate(MARGIN.toFloat(), MARGIN.toFloat())
        for (pt in corners) {
            val tx = (pt.x - srcX) / invScale
            val ty = (pt.y - srcY) / invScale
            canvas.drawText("⊕ ALIGN MARK", tx.toFloat() + 10, ty.toFloat() - 10, paint)
        }
        canvas.restore()
    }

    /**
     * Saves each stencil layer as a separate PNG to the device gallery.
     */
    suspend fun saveLayerPngs(
        context: Context,
        layers: List<StencilLayer>
    ) = withContext(Dispatchers.IO) {
        layers.forEach { layer ->
            val filename = "Stencil_${layer.type.label}_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GraffitiXR/Stencils")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return@forEach
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                layer.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
        }
    }
}
