package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.save
import app.benbow.app.resources.trackers_hours_from
import app.benbow.app.resources.trackers_hours_to
import app.benbow.app.resources.trackers_span_add
import app.benbow.app.resources.trackers_span_remove
import app.benbow.app.resources.trackers_spans_empty
import app.benbow.app.resources.untitled
import app.benbow.core.tracker.SpanField
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerRecord
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * A day's spans of a time column (ADR-050, B6): each from and to, as clocks; «Добавить отрезок», a span let go to the
 * trash. A night counted by its end starts the evening before. Written on «Сохранить» in one transaction ([spanEdits]),
 * nothing while a time doesn't read.
 */
@Composable
internal fun DaySpansDialog(column: TrackerColumn, day: LocalDate, spans: List<TrackerRecord>, write: TrackerWrite, onDismiss: () -> Unit) {
    val listed = remember(column.id, day) { spansOn(spans, "$day", column.dayBy) }
    var drafts by remember(column.id, day) {
        mutableStateOf(listed.map { SpanDraft(it.id, clockAt(it[SpanField.START]), clockAt(it[SpanField.END])) })
    }
    val edits = spanEdits(day, column.dayBy, drafts, listed)
    val months = stringArrayResource(Res.array.months_genitive)
    val change = { i: Int, draft: SpanDraft -> drafts = drafts.mapIndexed { j, each -> if (j == i) draft else each } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "${columnTitle(column, stringResource(Res.string.untitled))} · ${dateLabel("$day", months)}",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (drafts.none { !it.gone }) Hint(stringResource(Res.string.trackers_spans_empty))
                drafts.forEachIndexed { i, draft ->
                    if (!draft.gone) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                draft.start,
                                { change(i, draft.copy(start = it)) },
                                label = { Text(stringResource(Res.string.trackers_hours_from)) },
                                singleLine = true,
                                isError = typedClock(draft.start) == null,
                                modifier = Modifier.weight(1f).testTag("span-start:$i"),
                            )
                            OutlinedTextField(
                                draft.end,
                                { change(i, draft.copy(end = it)) },
                                label = { Text(stringResource(Res.string.trackers_hours_to)) },
                                singleLine = true,
                                isError = typedClock(draft.end) == null,
                                modifier = Modifier.weight(1f).testTag("span-end:$i"),
                            )
                            IconButton({ change(i, draft.copy(gone = true)) }, Modifier.hand()) {
                                Icon(Icons.Delete, stringResource(Res.string.trackers_span_remove))
                            }
                        }
                    }
                }
                TextButton({ drafts = drafts + newSpanDraft(column.dayBy) }, Modifier.hand()) {
                    Text(stringResource(Res.string.trackers_span_add))
                }
            }
        },
        confirmButton = {
            TextButton({
                edits?.takeIf { it.isNotEmpty() }?.let { list -> write { together { list.forEach { editSpan(column.id, it) } } } }
                onDismiss()
            }, Modifier.hand(edits != null), enabled = edits != null) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    )
}
