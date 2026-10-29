package app.benbow.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.voice_discard
import app.benbow.ui.theme.BenbowTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.math.log10
import kotlin.math.roundToInt

/** The dictaphone's two keys (7.10). */
internal enum class RecorderMode { Voice, Text }

/**
 * The keys' mechanics (7.10, ADR-033): a tap latches a key down until the next tap, holding records only while held,
 * and pressing one key while the other is down lets that one up first, its take kept (a tape deck's interlock).
 * [start] begins a take and says whether it could (the microphone, a permission); [finish] ends it, kept or not.
 */
@Stable
internal class RecorderKeys(private val start: (RecorderMode) -> Boolean, private val finish: (RecorderMode, keep: Boolean) -> Unit) {
    /** The key that's down, latched or held; null when both stand up. */
    var down by mutableStateOf<RecorderMode?>(null)
        private set

    /** True when [down] stays down after its finger left (a tap). */
    var latched by mutableStateOf(false)
        private set

    /** The current press started the take (rather than coming back to a latched key). */
    private var started = false

    fun press(key: RecorderMode) {
        started = false
        if (down == key) return // a latched key comes up when the finger leaves it
        up(keep = true)
        if (start(key)) {
            down = key
            started = true
        }
    }

    /** The finger left [key] after [heldMs]: a tap latches it; a hold, or the second tap of a latched key, lets it up. */
    fun release(key: RecorderMode, heldMs: Long) {
        if (down != key) return
        if (started && heldMs < TAP_MS) latched = true else up(keep = true)
        started = false
    }

    /** Lets the key that's down up: ✕ throws its take away, leaving the screen keeps it. */
    fun up(keep: Boolean) {
        val key = down ?: return
        down = null
        latched = false
        finish(key, keep)
    }

    /** The take ended by itself (the microphone failed): the key comes up without finishing it again. */
    fun ended(key: RecorderMode) {
        if (down != key) return
        down = null
        latched = false
    }

    private companion object {
        /** A press shorter than this is a tap: the key latches and the take goes on hands free. */
        const val TAP_MS = 350L
    }
}

/** The keys' housing: they sit side by side in one body, like a dictaphone's. */
@Composable
internal fun KeyHousing(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = BenbowTheme.colors.dark
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (dark) colors.surfaceContainerLow else colors.surfaceContainerHigh)
            .border(0.5.dp, colors.outlineVariant, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/**
 * One key of the dictaphone (7.10, ux.md): it stands a little raised, goes down fast under the finger with a firm
 * click and stays down while [down] (latched or held), its lamp lit in [lamp] and breathing with [level] (0..1); up
 * again it springs back with a small bounce and a softer click. A [locked] key goes only half way and back, with a
 * refusing buzz, and [onLocked] says why.
 */
@Composable
internal fun RecorderKey(
    icon: ImageVector,
    label: String,
    description: String,
    lamp: Color,
    down: Boolean,
    level: () -> Float,
    onPress: () -> Unit,
    onRelease: (heldMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    onLocked: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val refuse by rememberUpdatedState(onLocked)
    var touched by remember { mutableStateOf(false) }
    val travel = remember { Animatable(if (down) 1f else 0f) }
    val target = when {
        locked && touched -> 0.5f
        touched || down -> 1f
        else -> 0f
    }
    LaunchedEffect(target) {
        if (target > travel.value) {
            travel.animateTo(target, tween(55, easing = FastOutLinearInEasing))
        } else {
            travel.animateTo(target, spring(dampingRatio = 0.42f, stiffness = 900f)) // springs back past its rest a little
        }
    }
    var wasDown by remember { mutableStateOf(down) }
    LaunchedEffect(down) {
        if (down != wasDown) haptics.performHapticFeedback(if (down) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        wasDown = down
    }
    val colors = MaterialTheme.colorScheme
    val dark = BenbowTheme.colors.dark
    val face = if (dark) colors.surfaceContainerHighest else colors.surfaceContainerLowest
    val sunk = if (dark) colors.surfaceContainerHigh else colors.surfaceContainer
    val side = colors.outlineVariant
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier
            .size(KEY_WIDTH, KEY_HEIGHT)
            .hand()
            .semantics {
                role = Role.Button
                contentDescription = description
                if (down) stateDescription = label
                onClick {
                    if (locked) {
                        refuse()
                    } else {
                        press()
                        release(0)
                    }
                    true
                }
            }
            .pointerInput(locked) {
                awaitEachGesture {
                    val first = awaitFirstDown()
                    touched = true
                    if (locked) {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        refuse()
                        waitForUpOrCancellation()
                        touched = false
                        return@awaitEachGesture
                    }
                    press()
                    val up = waitForUpOrCancellation()
                    touched = false
                    release(if (up == null) Long.MAX_VALUE else up.uptimeMillis - first.uptimeMillis)
                }
            },
    ) {
        // The key's side, seen under its face while it stands up.
        Box(
            Modifier.fillMaxWidth().height(KEY_HEIGHT - TRAVEL).offset(y = TRAVEL).clip(shape).background(side),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(KEY_HEIGHT - TRAVEL)
                .offset { IntOffset(0, (travel.value * TRAVEL.toPx()).roundToInt()) }
                .clip(shape)
                .background(lerp(face, sunk, travel.value.coerceIn(0f, 1f)))
                .border(0.5.dp, side, shape),
        ) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    icon,
                    null,
                    Modifier.size(20.dp),
                    tint = if (locked) BenbowTheme.colors.disabled else colors.onSurface,
                )
                Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 6.dp).size(6.dp).drawBehind {
                    drawCircle(if (down) lamp.copy(alpha = 0.45f + 0.55f * level().coerceIn(0f, 1f)) else side)
                },
            )
        }
    }
}

/** What the row shows while a key is down (7.10): "Текст 0:12", the voice's level, phrases on their way, and ✕. */
@Composable
internal fun RecordingStrip(
    label: String,
    lamp: Color,
    elapsedMs: Long,
    level: Float,
    pending: Int,
    onDiscard: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${durationLabel(elapsedMs)}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.surfaceVariant)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0f, 1f)).background(lamp))
        }
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) { if (pending > 0) PendingDots(colors.onSurfaceVariant) }
        if (onDiscard != null) {
            IconButton(onDiscard, modifier = Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.voice_discard)) }
        }
    }
}

/** Three dots that come and go while phrases are being turned into text (drawn: the web build has no "…" glyph). */
@Composable
private fun PendingDots(color: Color) {
    val pulse by rememberInfiniteTransition().animateFloat(0.25f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse))
    Canvas(Modifier.size(20.dp, 6.dp)) {
        val r = size.height / 2
        for (i in 0..2) {
            val phase = ((pulse + i * 0.25f) % 1f).coerceIn(0.25f, 1f)
            drawCircle(color.copy(alpha = phase), r, center.copy(x = r + i * (size.width - 2 * r) / 2))
        }
    }
}

/** Loudness in RMS (0..1 of full scale) as a level for lamps and bars: -50 dBFS is nothing, -10 dBFS is full. */
internal fun levelOf(rms: Float): Float = if (rms <= 0f) 0f else ((20 * log10(rms) + 50) / 40).coerceIn(0f, 1f)

/** The lamps: red for a voice note, amber like the lantern for text. */
internal val VoiceLamp = Color(0xFFE5484D)
internal val TextLamp = Color(0xFFF0A030)

private val KEY_WIDTH = 60.dp
private val KEY_HEIGHT = 48.dp

/** How far a key travels, and how much of its side shows while it stands up. */
private val TRAVEL = 4.dp
