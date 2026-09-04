// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/model/EditorModels.kt
package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import com.hereliesaz.graffitixr.common.serialization.BlendModeSerializer
import com.hereliesaz.graffitixr.common.serialization.OffsetSerializer
import com.hereliesaz.graffitixr.common.serialization.UriSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents a single graphical layer in the editor with all aesthetic and spatial parameters.
 * Serializable fields cover all wire-transferable state. Bitmap is runtime-only and excluded.
 */
@Serializable
data class Layer(
    val id: String,
    val name: String,
    @Serializable(with = UriSerializer::class)
    val uri: Uri? = null,
    @Transient
    val bitmap: Bitmap? = null,
    val isVisible: Boolean = true,
    val opacity: Float = 1.0f,
    val brightness: Float = 0.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val colorBalanceR: Float = 1.0f,
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f,
    val isImageLocked: Boolean = false,
    val isLinked: Boolean = false,
    @Serializable(with = BlendModeSerializer::class)
    val blendMode: BlendMode = BlendMode.SrcOver,
    @Serializable(with = OffsetSerializer::class)
    val offset: Offset = Offset.Zero,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val scale: Float = 1.0f,
    val isInverted: Boolean = false,
    /**
     * Outline effect: the image is shown as a pencil sketch — dark lines opaque, light areas
     * transparent — which is what you actually want to trace from.
     *
     * A flag rather than a baked bitmap, because [uri] always points at the untouched import. The
     * effects are re-derived from it on load, so toggling one off gets the original back exactly
     * and no round trip degrades the source.
     */
    val isSketch: Boolean = false,
    /** Subject isolation: everything but the segmented subject is made transparent. */
    val isSubjectIsolated: Boolean = false,
)

/**
 * The subset of the design's visual/aesthetic properties that can be changed without replacing
 * the whole design. Used by Op.DesignProps to stream property-only mutations over the wire.
 */
@Serializable
data class LayerProps(
    val isVisible: Boolean = true,
    val opacity: Float = 1.0f,
    val brightness: Float = 0.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val colorBalanceR: Float = 1.0f,
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f,
    val isImageLocked: Boolean = false,
    val isInverted: Boolean = false,
    @Serializable(with = BlendModeSerializer::class)
    val blendMode: BlendMode = BlendMode.SrcOver
)

/**
 * A completed brush stroke, coarse-grained: only emitted once the user lifts their finger.
 * Points are encoded as interleaved [x0, y0, x1, y1, ...] for compact wire transfer.
 */
@Serializable
data class BrushStroke(
    val points: List<Float> = emptyList(),
    val colorArgb: Long = 0xFFFFFFFF,
    val brushSize: Float = 50f,
    val brushFeathering: Float = 0f,
    val blendModeOrdinal: Int = 3 // BlendMode.SrcOver ordinal
)

/**
 * Available operational modes for the GraffitiXR environment.
 */
enum class EditorMode {
    TRACE,
    MOCKUP,
    OVERLAY,
    AR,
    DESIGN
}

/**
 * UI panels available for interaction within the editor interface.
 */
enum class EditorPanel {
    NONE,
    LAYERS,
    ADJUSTMENTS,
    TRANSFORM,
    COLOR,
    ADJUST
}

/**
 * Transformation axes for 3D manipulation.
 */
enum class RotationAxis {
    X,
    Y,
    Z
}

/**
 * Whole-design adjustment applied per [EditorMode]. Lets the user position and tone the entire
 * mural as a single unit for one mode (e.g. line it up on a wall in MOCKUP) without altering the
 * underlying Design layers. Identity = no change. Persisted per mode; Design edits stay global.
 */
@Serializable
data class ModeAdjustment(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    // rotation = rotation about the layer's normal (Z). rotationX/rotationY tilt the layer about its
    // own width (X) / height (Y) axes — used in AR so a double-tap can switch the active rotation axis
    // and the artwork tilts in 3D about its own axes (not just spin in-plane). Default 0 keeps old
    // projects identity on deserialize.
    val rotation: Float = 0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val opacity: Float = 1f,
    val isInverted: Boolean = false,
    // When true, pan/zoom/rotate gestures for this mode are ignored — the user has positioned the
    // whole-design layer and locked it in place (e.g. a Trace reference that must not drift while
    // tracing). Tone/opacity edits and the lightbox touch-lock are independent of this.
    val isTransformLocked: Boolean = false,
) {
    val isIdentity: Boolean
        get() = offsetX == 0f && offsetY == 0f && scale == 1f &&
            rotation == 0f && rotationX == 0f && rotationY == 0f &&
            brightness == 0f && contrast == 1f && saturation == 1f && opacity == 1f && !isInverted
}

/**
 * The transform the Reset button put aside, so a second press can put it back.
 *
 * Reset is a toggle, not a destructive action: the first press flattens placement to identity, the
 * second returns the design to exactly where it was. Only transform is captured — tone, colour
 * balance and effects are deliberately untouched by Reset, so they must survive both presses.
 *
 * [mode] records which mode the stash was taken in, because the mode transform is per-mode: after
 * switching modes the stashed values no longer describe what is on screen, so the next press resets
 * afresh rather than restoring something the user never reset.
 */
data class TransformStash(
    val mode: EditorMode,
    val designScale: Float,
    val designOffset: Offset,
    val designRotationX: Float,
    val designRotationY: Float,
    val designRotationZ: Float,
    val modeOffsetX: Float,
    val modeOffsetY: Float,
    val modeScale: Float,
    val modeRotation: Float,
    val modeRotationX: Float,
    val modeRotationY: Float,
)

/**
 * The global state for the Editor UI, including AR and Gesture feedback flags.
 */
data class EditorUiState(
    val projectId: String? = null,
    /**
     * The one overlay image being placed, or null before one is chosen.
     *
     * Singular by design: this app positions a finished image against a wall, and authoring —
     * including any compositing of several images into one — belongs to the companion design app.
     * It was a `List<Layer>` with an `activeLayerId`, which cost every consumer a lookup and a
     * null-or-empty distinction for a collection that in practice held one element. [Layer] keeps
     * its name because it still carries exactly the per-image state the adjustment knobs drive.
     */
    val design: Layer? = null,
    /**
     * A picked image awaiting confirmation because it would replace [design] rather than add
     * alongside it — there is exactly one design slot (see [design]'s doc), so a second pick is
     * destructive to the first. Null when no picker result is pending confirmation.
     */
    val pendingReplaceUri: Uri? = null,
    val backgroundBitmap: Bitmap? = null,
    val activePanel: EditorPanel = EditorPanel.NONE,
    val editorMode: EditorMode = EditorMode.AR,
    val hideUiForCapture: Boolean = false,
    val isRightHanded: Boolean = true,
    val gestureInProgress: Boolean = false,
    val showRotationAxisFeedback: Boolean = false,
    // Transient, self-clearing (mirrors showRotationAxisFeedback's pattern) — set true when a
    // transform gesture or Reset is silently ignored because the current mode's transform is
    // locked. Without this, a locked mode swallowed gestures and Reset with zero feedback: no
    // toast, no shake, no dimmed control, only a color highlight on a rail sub-item that may be
    // on a collapsed or folded-away host.
    val showLockedFeedback: Boolean = false,
    // Transient, self-clearing — a one-shot failure toast for editor actions that used to fail
    // silently: Isolate/Outline falling back to their unchanged input (the rail highlight for the
    // effect still lit up as "done" regardless, since it's keyed on the boolean toggle, not on
    // whether the bitmap actually changed), and a Mockup wall-photo pick the decoder rejected
    // (spinner, then nothing). Generic text rather than a sealed type since every current use is
    // "tell the user this one action didn't work," with no differing UI per cause.
    val effectFailureMessage: String? = null,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val undoCount: Int = 0,
    val redoCount: Int = 0,
    val isLoading: Boolean = false,
    val showDiagOverlay: Boolean = false,
    // In-world perception layers, each independently toggleable from Settings.
    // Distinct from showDiagOverlay, which governs the text telemetry HUD.
    // showFeaturePoints is a raw engineering/perception-debug visualization with no corresponding
    // onboarding explanation anywhere in the app, so it defaults OFF for ordinary users.
    // showPlaneGrids and showPoints default ON: the AR onboarding copy (onboarding_ar[1], "Coloured
    // shapes appear as the engine maps the surface") explicitly promises scanning feedback, and the
    // accumulated point cloud is the visible result of that scan, so leaving both on is intentional,
    // not an oversight.
    val showFeaturePoints: Boolean = false,  // ARCore sparse feature dots (tracker landmarks)
    val showPlaneGrids: Boolean = true,      // detected planes as metric grids
    val showPoints: Boolean = true,          // accumulated sparse point cloud
    val canvasBackground: Color = Color.Black,
    // Per-mode whole-design adjustments (transform + tone). Applied to the composited design in
    // that mode only; Design-mode layer edits stay global across all modes.
    val modeAdjustments: Map<EditorMode, ModeAdjustment> = emptyMap(),
    /**
     * Non-null while a Reset is in effect: the placement the next Reset press restores. Cleared by
     * any transform the user makes afterwards, so Reset never puts back a position they have since
     * moved away from. Runtime-only — a Reset does not survive reopening the project.
     */
    val transformStash: TransformStash? = null,
)

