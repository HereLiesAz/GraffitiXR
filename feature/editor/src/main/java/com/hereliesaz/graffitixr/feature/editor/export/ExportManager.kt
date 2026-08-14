// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/export/ExportManager.kt
package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import android.graphics.BlendMode as NativeBlendMode
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.feature.editor.createColorMatrix
import javax.inject.Inject

/**
 * Renders the design onto an export canvas.
 */
class ExportManager @Inject constructor() {

    /**
     * Draws [design] (with its tone and transform applied) over the background, if there is one.
     *
     * [modeAdj] is the whole-design ModeAdjustment for the mode being exported from (e.g.
     * `uiState.modeAdjustments[uiState.editorMode]`) — every gesture and tone control outside
     * DESIGN mode writes here, not to the layer itself, so omitting it meant an Overlay/Mockup/
     * Trace export always showed the design at its untouched default position/scale/opacity,
     * regardless of how it had actually been placed on screen. Composed on top of the layer's own
     * transform/tone exactly the way MainScreen's on-screen graphicsLayer nesting does: tone is
     * layer*mode (saturation/contrast multiply, brightness adds, invert XORs), opacity multiplies,
     * and geometry wraps the layer's own screen matrix in an outer scale/rotate/translate pivoted
     * on the export canvas center. Only the Z rotation is applied — [ModeAdjustment.rotationX]/
     * [ModeAdjustment.rotationY] are a live 3D tilt the on-screen renderer fakes with a camera
     * projection; a flat 2D export canvas can't reproduce that, the same limitation
     * [getLayerScreenMatrix] already accepts for the layer's own rotationX/rotationY. Passing null
     * (the default) reproduces the pre-existing design-only behaviour exactly.
     */
    fun composite(
        design: Layer?,
        width: Int,
        height: Int,
        backgroundBitmap: Bitmap? = null,
        backgroundColor: Int = android.graphics.Color.TRANSPARENT,
        modeAdj: ModeAdjustment? = null,
    ): Bitmap {
        // Bitmap.createBitmap throws on a non-positive dimension. Callers pass display metrics, and
        // those read back as 0 on a detached/not-yet-laid-out display — clamp here so no call site
        // can turn that into a crash mid-export.
        val screenWidth = width.coerceAtLeast(1)
        val screenHeight = height.coerceAtLeast(1)
        val result = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        if (backgroundColor != android.graphics.Color.TRANSPARENT) {
            canvas.drawColor(backgroundColor)
        }

        backgroundBitmap?.let { bg ->
            val bgAspect = bg.width.toFloat() / bg.height.toFloat()
            val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()

            var renderWidth = screenWidth.toFloat()
            var renderHeight = screenHeight.toFloat()

            if (bgAspect > screenAspect) {
                renderWidth = renderHeight * bgAspect
            } else {
                renderHeight = renderWidth / bgAspect
            }

            val matrix = Matrix()
            matrix.postScale(renderWidth / bg.width, renderHeight / bg.height)
            matrix.postTranslate((screenWidth - renderWidth) / 2f, (screenHeight - renderHeight) / 2f)

            canvas.drawBitmap(bg, matrix, null)
        }

        design?.takeIf { it.isVisible }?.let { layer ->
            layer.bitmap?.let { b ->
                // Whole-design tone on top of the layer's own — same fold as MainScreen's on-screen
                // ColorFilter for Overlay/Mockup/Trace. Defaults (1/1/0/false) leave the layer's own
                // values untouched when modeAdj is null (Design mode / unspecified).
                val cm = createColorMatrix(
                    saturation = layer.saturation * (modeAdj?.saturation ?: 1f),
                    contrast = layer.contrast * (modeAdj?.contrast ?: 1f),
                    brightness = layer.brightness + (modeAdj?.brightness ?: 0f),
                    colorBalanceR = layer.colorBalanceR,
                    colorBalanceG = layer.colorBalanceG,
                    colorBalanceB = layer.colorBalanceB,
                    isInverted = layer.isInverted != (modeAdj?.isInverted ?: false)
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    // Nested alpha in the on-screen graphicsLayer is multiplicative (outer mode alpha
                    // wrapping the inner layer alpha), so it is here too.
                    alpha = (layer.opacity * (modeAdj?.opacity ?: 1f) * 255).toInt().coerceIn(0, 255)
                    applyLayerBlendMode(layer.blendMode)
                    colorFilter = android.graphics.ColorMatrixColorFilter(
                        android.graphics.ColorMatrix(cm.values)
                    )
                }

                val matrix = getLayerScreenMatrix(layer, screenWidth, screenHeight, modeAdj)
                canvas.drawBitmap(b, matrix, paint)
            }
        }
        return result
    }

    private fun getLayerScreenMatrix(
        layer: Layer,
        screenWidth: Int,
        screenHeight: Int,
        modeAdj: ModeAdjustment? = null,
    ): Matrix {
        val b = layer.bitmap ?: return Matrix()
        val matrix = Matrix()

        // Calculate ContentScale.Fit logic so the exported image matches the UI layout bounds
        val imageAspect = b.width.toFloat() / b.height.toFloat()
        val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()

        var renderWidth = screenWidth.toFloat()
        var renderHeight = screenHeight.toFloat()

        if (imageAspect > screenAspect) {
            renderHeight = renderWidth / imageAspect
        } else {
            renderWidth = renderHeight * imageAspect
        }

        // 1. Initial Scale to screen constraints
        matrix.postScale(renderWidth / b.width, renderHeight / b.height)

        // 2. Center Pivot
        matrix.postTranslate(-renderWidth / 2f, -renderHeight / 2f)

        // 3. User Transforms (Scale, Rotate, Offset)
        matrix.postScale(layer.scale, layer.scale)
        matrix.postRotate(layer.rotationZ) // Standard 2D export only respects Z

        // 4. Move to center of screen + apply pan
        matrix.postTranslate(screenWidth / 2f + layer.offset.x, screenHeight / 2f + layer.offset.y)

        // 5. Whole-design (mode) transform, wrapping the layer's own the same way MainScreen's
        // outer graphicsLayer wraps the inner one on screen — pivoted at screen center, matching
        // that graphicsLayer's fillMaxSize() + TransformOrigin.Center. Z rotation only; see this
        // function's caller doc for why X/Y tilt can't be reproduced on a flat export canvas.
        if (modeAdj != null) {
            val centerX = screenWidth / 2f
            val centerY = screenHeight / 2f
            matrix.postScale(modeAdj.scale, modeAdj.scale, centerX, centerY)
            matrix.postRotate(modeAdj.rotation, centerX, centerY)
            matrix.postTranslate(modeAdj.offsetX, modeAdj.offsetY)
        }

        return matrix
    }

    /**
     * Sets this paint's blend mode for [mode]. `Paint.blendMode` and `android.graphics.BlendMode`
     * are both API 29, but this module's minSdk is 26 — assigning it unconditionally threw
     * NoClassDefFoundError on API 26-28 the moment anything composited (export, share, thumbnail,
     * flatten, stencil). Below 29, fall back to the PorterDuff Xfermode equivalent; the handful of
     * separable/non-separable modes PorterDuff can't express degrade to SRC_OVER there.
     */
    private fun Paint.applyLayerBlendMode(mode: BlendMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            blendMode = mode.toNativeBlendMode()
        } else {
            xfermode = mode.toPorterDuffMode()?.let { PorterDuffXfermode(it) }
        }
    }

    /**
     * The PorterDuff equivalent of [BlendMode] for API < 29, or null (leaving the paint's default
     * SRC_OVER) for modes PorterDuff.Mode has no counterpart for.
     */
    private fun BlendMode.toPorterDuffMode(): PorterDuff.Mode? = when (this) {
        BlendMode.Clear     -> PorterDuff.Mode.CLEAR
        BlendMode.Src       -> PorterDuff.Mode.SRC
        BlendMode.Dst       -> PorterDuff.Mode.DST
        BlendMode.SrcOver   -> PorterDuff.Mode.SRC_OVER
        BlendMode.DstOver   -> PorterDuff.Mode.DST_OVER
        BlendMode.SrcIn     -> PorterDuff.Mode.SRC_IN
        BlendMode.DstIn     -> PorterDuff.Mode.DST_IN
        BlendMode.SrcOut    -> PorterDuff.Mode.SRC_OUT
        BlendMode.DstOut    -> PorterDuff.Mode.DST_OUT
        BlendMode.SrcAtop   -> PorterDuff.Mode.SRC_ATOP
        BlendMode.DstAtop   -> PorterDuff.Mode.DST_ATOP
        BlendMode.Xor       -> PorterDuff.Mode.XOR
        BlendMode.Plus      -> PorterDuff.Mode.ADD
        BlendMode.Modulate  -> PorterDuff.Mode.MULTIPLY
        BlendMode.Multiply  -> PorterDuff.Mode.MULTIPLY
        BlendMode.Screen    -> PorterDuff.Mode.SCREEN
        BlendMode.Overlay   -> PorterDuff.Mode.OVERLAY
        BlendMode.Darken    -> PorterDuff.Mode.DARKEN
        BlendMode.Lighten   -> PorterDuff.Mode.LIGHTEN
        // ColorDodge / ColorBurn / Hardlight / Softlight / Difference / Exclusion / Hue /
        // Saturation / Color / Luminosity have no PorterDuff.Mode counterpart.
        else -> null
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun BlendMode.toNativeBlendMode(): NativeBlendMode {
        return when (this) {
            BlendMode.Clear -> NativeBlendMode.CLEAR
            BlendMode.Src -> NativeBlendMode.SRC
            BlendMode.Dst -> NativeBlendMode.DST
            BlendMode.SrcOver -> NativeBlendMode.SRC_OVER
            BlendMode.DstOver -> NativeBlendMode.DST_OVER
            BlendMode.SrcIn -> NativeBlendMode.SRC_IN
            BlendMode.DstIn -> NativeBlendMode.DST_IN
            BlendMode.SrcOut -> NativeBlendMode.SRC_OUT
            BlendMode.DstOut -> NativeBlendMode.DST_OUT
            BlendMode.SrcAtop -> NativeBlendMode.SRC_ATOP
            BlendMode.DstAtop -> NativeBlendMode.DST_ATOP
            BlendMode.Xor -> NativeBlendMode.XOR
            BlendMode.Plus -> NativeBlendMode.PLUS
            BlendMode.Modulate -> NativeBlendMode.MODULATE
            BlendMode.Screen -> NativeBlendMode.SCREEN
            BlendMode.Overlay -> NativeBlendMode.OVERLAY
            BlendMode.Darken -> NativeBlendMode.DARKEN
            BlendMode.Lighten -> NativeBlendMode.LIGHTEN
            BlendMode.ColorDodge -> NativeBlendMode.COLOR_DODGE
            BlendMode.ColorBurn -> NativeBlendMode.COLOR_BURN
            BlendMode.Hardlight -> NativeBlendMode.HARD_LIGHT
            BlendMode.Softlight -> NativeBlendMode.SOFT_LIGHT
            BlendMode.Difference -> NativeBlendMode.DIFFERENCE
            BlendMode.Exclusion -> NativeBlendMode.EXCLUSION
            BlendMode.Multiply -> NativeBlendMode.MULTIPLY
            BlendMode.Hue -> NativeBlendMode.HUE
            BlendMode.Saturation -> NativeBlendMode.SATURATION
            BlendMode.Color -> NativeBlendMode.COLOR
            BlendMode.Luminosity -> NativeBlendMode.LUMINOSITY
            else -> NativeBlendMode.SRC_OVER
        }
    }
}
