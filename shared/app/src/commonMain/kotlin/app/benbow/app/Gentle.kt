package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.curtain_open
import app.benbow.app.resources.gentle
import app.benbow.app.resources.gentle_off_hint
import app.benbow.app.resources.gentle_on_hint
import app.benbow.app.resources.veil_curtain
import app.benbow.app.resources.veil_quiet
import app.benbow.app.resources.veil_uncurtain
import app.benbow.app.resources.veil_unquiet
import app.benbow.client.EntrySummary
import app.benbow.client.Veil
import app.benbow.ui.editor.rememberSpoilerDots
import app.benbow.ui.editor.spoilerClock
import app.benbow.ui.editor.spoilerSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * Бережно (ADR-031): whether curtains and quiet entries apply on this device right now. On in every new process (it is
 * never stored); turned off, it comes back by itself once the app has been away for five minutes. Opening is
 * temporary, closing lasts.
 */
class Gentle internal constructor(private val now: () -> Long) {
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on
    private var leftAt: Long? = null

    fun set(on: Boolean) {
        _on.value = on
    }

    fun onLeave() {
        leftAt = now()
    }

    fun onReturn() {
        val left = leftAt ?: return
        leftAt = null
        if (now() - left >= AWAY_MILLIS) _on.value = true
    }

    companion object {
        const val AWAY_MILLIS = 5 * 60_000L
    }
}

/** Whether Бережно applies right now ([Gentle.on]). */
internal val LocalGentle = compositionLocalOf { true }

/** Under a curtain right now: any veiled entry while Бережно is on (passive lists leave the quiet ones out first). */
internal fun EntrySummary.curtained(gentle: Boolean) = gentle && veil != null

/** A passive list (Feed, Home) without the quiet entries while Бережно is on. */
internal fun <T> List<T>.withoutQuiet(gentle: Boolean, summary: (T) -> EntrySummary): List<T> =
    if (gentle) filter { summary(it).veil != Veil.Quiet } else this

/** The curtain: shimmering dots at one compact height with "Открыть", whatever the entry holds (its length too). */
@Composable
internal fun Curtain(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.onSurface.copy(alpha = 0.05f))
            .spoilerSurface(rememberSpoilerDots(), colors.onSurface, spoilerClock(true)) { CURTAIN_DOTS },
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(50)) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Eye, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Text(stringResource(Res.string.curtain_open), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** The switch, kept out of sight: the Feed shows it when pulled down at its top (or its title is clicked). */
@Composable
internal fun GentleStrip(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.toggleable(on, role = Role.Switch, onValueChange = onChange).hand().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Leaf, null, Modifier.size(22.dp), tint = colors.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.gentle), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(if (on) Res.string.gentle_on_hint else Res.string.gentle_off_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Switch(on, null)
        }
    }
}

/**
 * A long press on something that also takes clicks (the Feed tab): it runs [onLongPress] and swallows the rest of
 * the touch, so no click follows; a short tap passes through untouched.
 */
internal fun Modifier.longPress(onLongPress: () -> Unit): Modifier = pointerInput(onLongPress) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var released = false
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation(PointerEventPass.Initial)
            released = true
        }
        if (released) return@awaitEachGesture
        onLongPress()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * "Задёрнуть шторку" and "Не показывать в ленте" (or taking them back) for an entry's menu. [veil] is what the entry
 * shows as, [ruled] what the rules alone give; [onPick] gets the entry's own new mark: taking a veil back from an
 * entry a rule still covers marks it open ([Veil.Open]), so it shows although the rule stays.
 */
@Composable
internal fun VeilMenuItems(veil: Veil?, ruled: Veil?, onPick: (Veil?) -> Unit) {
    val shown = if (ruled != null) Veil.Open else null
    DropdownMenuItem(
        text = { Text(stringResource(if (veil == Veil.Curtain) Res.string.veil_uncurtain else Res.string.veil_curtain)) },
        onClick = { onPick(if (veil == Veil.Curtain) shown else Veil.Curtain) },
        modifier = Modifier.hand(),
    )
    DropdownMenuItem(
        text = { Text(stringResource(if (veil == Veil.Quiet) Res.string.veil_unquiet else Res.string.veil_quiet)) },
        onClick = { onPick(if (veil == Veil.Quiet) shown else Veil.Quiet) },
        modifier = Modifier.hand(),
    )
}

/** A curtain is calmer than a spoiler: its dots are dimmer. */
private const val CURTAIN_DOTS = 0.6f
