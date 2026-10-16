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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.curtain_open
import app.benbow.app.resources.gentle
import app.benbow.app.resources.gentle_off_hint
import app.benbow.app.resources.gentle_on_hint
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.postpone
import app.benbow.app.resources.postpone_back_now
import app.benbow.app.resources.postpone_choices
import app.benbow.app.resources.postpone_hint
import app.benbow.app.resources.postpone_pick_day
import app.benbow.app.resources.returned_again
import app.benbow.app.resources.returned_hint
import app.benbow.app.resources.returned_later
import app.benbow.app.resources.returned_title
import app.benbow.app.resources.save
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
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

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

/** "Отложить до…", or "Вернуть сейчас" while the entry is put off; [onClick] gets true to choose a day. */
@Composable
internal fun PostponeMenuItem(until: String?, onClick: (choose: Boolean) -> Unit) {
    val away = until != null && journalToday().toString() < until
    DropdownMenuItem(
        text = { Text(stringResource(if (away) Res.string.postpone_back_now else Res.string.postpone)) },
        onClick = { onClick(!away) },
        modifier = Modifier.hand(),
    )
}

/** When a put-off entry comes back: in a week, a month, half a year, a year, or on a chosen day (after today). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostponeDialog(onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = remember { journalToday() }
    var choosing by remember { mutableStateOf(false) }
    if (choosing) {
        // The date picker speaks UTC midnights.
        val first = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val dates = rememberDatePickerState(
            initialSelectedDateMillis = first,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= first
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton({
                    dates.selectedDateMillis?.let { onPick(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date) }
                }, Modifier.hand()) { Text(stringResource(Res.string.save)) }
            },
            dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
        ) { DatePicker(dates) }
        return
    }
    val choices = stringArrayResource(Res.array.postpone_choices)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.postpone)) },
        text = {
            Column {
                Text(stringResource(Res.string.postpone_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                POSTPONE_PERIODS.forEachIndexed { i, period ->
                    TextButton({ onPick(today.plus(period)) }, Modifier.hand()) { Text(choices[i]) }
                }
                TextButton({ choosing = true }, Modifier.hand()) { Text(stringResource(Res.string.postpone_pick_day)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    )
}

private val POSTPONE_PERIODS = listOf(DatePeriod(days = 7), DatePeriod(months = 1), DatePeriod(months = 6), DatePeriod(years = 1))

/** A put-off entry is back (its day came): said once, calmly, without its text; it is already in its place again. */
@Composable
internal fun ReturnedCard(entry: EntrySummary, onOpen: () -> Unit, onLater: () -> Unit, onAgain: () -> Unit) {
    HomeCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Leaf, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(Res.string.returned_title, dateLabel(entry.day, stringArrayResource(Res.array.months_genitive))),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            stringResource(Res.string.returned_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onLater, Modifier.hand()) { Text(stringResource(Res.string.returned_later)) }
            TextButton(onAgain, Modifier.hand()) { Text(stringResource(Res.string.returned_again)) }
            TextButton(onOpen, Modifier.hand()) { Text(stringResource(Res.string.curtain_open)) }
        }
    }
}

/** "16 октября", with the year when it isn't this one; never "Сегодня" or "Вчера" (a date said as a date). */
internal fun dateLabel(day: String, months: List<String>): String {
    val date = LocalDate.parse(day)
    return "${date.day} ${months[date.month.number - 1]}" + if (date.year != journalToday().year) " ${date.year}" else ""
}
