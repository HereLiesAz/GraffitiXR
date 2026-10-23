package com.hereliesaz.graffitixr.design.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.theme.Danger
import com.hereliesaz.graffitixr.design.theme.HudPillShape
import com.hereliesaz.graffitixr.design.theme.HudScrim
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One transient message: [long] stays up longer; [error] gets the danger outline. */
data class HudMessage(val text: String, val long: Boolean = false, val error: Boolean = false)

/**
 * The app's transient messages, in place of platform Toasts: any layer posts here
 * ([show], callable from any thread) and [HudMessageHost] draws them as a branded HUD pill over
 * whatever screen is up. A burst keeps only the newest few.
 */
object HudMessages {
    private val _messages = MutableSharedFlow<HudMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<HudMessage> = _messages.asSharedFlow()

    fun show(text: String, long: Boolean = false, error: Boolean = false) {
        _messages.tryEmit(HudMessage(text, long, error))
    }

    @Volatile private var appContext: android.content.Context? = null

    /** Called once from Application.onCreate, so [appString] can resolve text outside a composition. */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    /** [id] in the app's current language, for messages posted from callbacks with no composition. */
    fun appString(@androidx.annotation.StringRes id: Int): String = appContext?.getString(id) ?: ""
}

/**
 * Draws [HudMessages] one at a time, each for 2 s (3.5 s when long), in a popup above the bottom
 * edge, so it shows over every screen wherever it is composed. Place it once, at the root.
 */
@Composable
fun HudMessageHost(modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<HudMessage?>(null) }
    LaunchedEffect(Unit) {
        HudMessages.messages.collect { message ->
            current = message
            delay(if (message.long) LONG_MS else SHORT_MS)
            current = null
            delay(GAP_MS)
        }
    }
    if (current == null) return
    Popup(
        alignment = Alignment.BottomCenter,
        offset = IntOffset(0, -BOTTOM_OFFSET_PX),
        properties = PopupProperties(focusable = false),
    ) {
    AnimatedVisibility(visible = current != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val message = current ?: return@AnimatedVisibility
        Box(
            Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 16.dp)
                .background(HudScrim, HudPillShape)
                .then(if (message.error) Modifier.border(1.dp, Danger, HudPillShape) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            Text(message.text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
    }
}

private const val SHORT_MS = 2_000L
private const val LONG_MS = 3_500L
private const val GAP_MS = 150L

// Above the knob row and the system navigation bar.
private const val BOTTOM_OFFSET_PX = 360
