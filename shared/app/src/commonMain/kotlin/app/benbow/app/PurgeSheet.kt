package app.benbow.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.benbow.app.resources.Res
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.purge_changed
import app.benbow.app.resources.purge_hold
import app.benbow.app.resources.purge_kinds
import app.benbow.app.resources.purge_no_files
import app.benbow.app.resources.purge_not_arrived
import app.benbow.app.resources.purge_offline
import app.benbow.app.resources.purge_retry
import app.benbow.app.resources.purge_staying_live
import app.benbow.app.resources.purge_staying_trash
import app.benbow.app.resources.purge_summary
import app.benbow.app.resources.purge_title
import app.benbow.app.resources.purge_when
import app.benbow.app.resources.server_cancel
import app.benbow.client.ApiException
import app.benbow.core.api.PurgeDone
import app.benbow.core.api.PurgePreview
import app.benbow.core.api.PurgeRequest
import app.benbow.core.api.StorageKind
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** The day ("YYYY-MM-DD") [millis] falls on here. */
internal fun dayOf(millis: Long): String =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

/**
 * «Удалить навсегда» (ADR-041, docs/ux.md): what the server says it would delete (a strip of what goes, how many and how
 * heavy, what stays and why, when the space is free), and a red button that acts only when held for about 1.5 s, the
 * fill running across it and a haptic at the end: no stray tap, and no word to type. The server does it or nothing; if
 * anything moved meanwhile, the sheet shows the new count. Offline it can't: only the server deletes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PurgeSheet(services: AppServices, request: PurgeRequest, onDone: (PurgeDone) -> Unit, onDismiss: () -> Unit) {
    var preview by remember { mutableStateOf<PurgePreview?>(null) }
    var failed by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(request, attempt) {
        failed = false
        preview = try {
            services.purgePreviewer(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
            null
        }
    }
    val commit = {
        val plan = preview
        if (plan != null && !committing) {
            committing = true
            scope.launch {
                try {
                    onDone(services.purgeCommitter(plan.planId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    // Something in the plan moved (409) or it expired (410): count again and show that.
                    if (e.status == 409 || e.status == 410) {
                        changed = true
                        attempt++
                    } else {
                        failed = true
                    }
                } catch (_: Exception) {
                    failed = true
                } finally {
                    committing = false
                }
            }
        }
    }
    val content = @Composable {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(Res.string.purge_title), style = MaterialTheme.typography.titleLarge)
            val plan = preview
            when {
                failed -> {
                    Text(stringResource(Res.string.purge_offline), color = MaterialTheme.colorScheme.error)
                    TextButton({ attempt++ }, Modifier.hand()) { Text(stringResource(Res.string.purge_retry)) }
                }

                plan == null -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                else -> PlanSummary(services, plan, changed)
            }
            HoldToConfirm(stringResource(Res.string.purge_hold), enabled = plan != null && !failed && !committing, onConfirm = commit)
            TextButton(onDismiss, Modifier.align(Alignment.End).hand()) { Text(stringResource(Res.string.server_cancel)) }
        }
    }
    if (LocalWide.current) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 520.dp)) {
                Column(Modifier.padding(top = 16.dp)) { content() }
            }
        }
    } else {
        // Open whole: the held button never sits below the screen's edge.
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            content()
        }
    }
}

@Composable
private fun PlanSummary(services: AppServices, plan: PurgePreview, changed: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (changed) Text(stringResource(Res.string.purge_changed), color = MaterialTheme.colorScheme.tertiary)
    if (plan.files.isEmpty()) {
        Text(stringResource(Res.string.purge_no_files), color = muted)
    } else {
        Text(
            pluralStringResource(Res.plurals.purge_summary, plan.files.size, plan.files.size, sizeLabel(plan.bytes)),
            style = MaterialTheme.typography.titleMedium,
        )
        // What goes, as this device has it: pictures it holds, a sign for the rest (nothing is downloaded to be deleted).
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(plan.files.take(STRIP), key = { it.sha256 }) { file ->
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .semantics { contentDescription = file.name ?: file.kind.orEmpty() },
                    contentAlignment = Alignment.Center,
                ) {
                    val local = services.media.localFile(file.sha256)?.takeIf { file.kind == "image" }
                    if (local != null) {
                        AsyncImage(local, null, Modifier.size(56.dp), contentScale = ContentScale.Crop)
                    } else {
                        Icon(
                            when (file.kind) {
                                "video" -> Icons.Play
                                "voice", "audio" -> Icons.Mic
                                else -> Icons.Attach
                            },
                            null,
                            tint = muted,
                        )
                    }
                }
            }
        }
        // A big purge says what it is made of, kind by kind.
        if (plan.files.size > MANY || plan.bytes > BIG) {
            val byKind = plan.files.groupBy { StorageKind.of(it.kind ?: "file") }
            val names = stringArrayResource(Res.array.purge_kinds)
            StorageKind.ALL.filter { it in byKind }.forEach { kind ->
                val files = byKind.getValue(kind)
                Row(Modifier.fillMaxWidth()) {
                    Text(names.getOrElse(StorageKind.ALL.indexOf(kind)) { kind }, Modifier.weight(1f), color = muted)
                    Text("${files.size} · ${sizeLabel(files.sumOf { it.size })}")
                }
            }
        }
    }
    val live = plan.staying.filter { it.live }
    val trash = plan.staying.filter { !it.live }
    if (live.isNotEmpty()) {
        Text(pluralStringResource(Res.plurals.purge_staying_live, live.size, live.size, sizeLabel(live.sumOf { it.size })), color = muted)
    }
    if (trash.isNotEmpty()) {
        Text(
            pluralStringResource(
                Res.plurals.purge_staying_trash,
                trash.size,
                trash.size,
                sizeLabel(
                    trash.sumOf {
                        it.size
                    },
                ),
            ),
            color = muted,
        )
    }
    if (plan.notArrived.isNotEmpty()) {
        Text(pluralStringResource(Res.plurals.purge_not_arrived, plan.notArrived.size, plan.notArrived.size), color = muted)
    }
    val months = stringArrayResource(Res.array.months_genitive)
    Text(stringResource(Res.string.purge_when, dateLabel(dayOf(plan.freesOn), months)), color = muted)
}

/**
 * A button that acts only when held for [HOLD_MILLIS]: a fill runs across it while it is held, letting go early empties
 * it again, the end gives a haptic. Screen readers get it as a long press.
 */
@Composable
internal fun HoldToConfirm(label: String, enabled: Boolean, onConfirm: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val progress = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val confirm by rememberUpdatedState(onConfirm)
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(if (enabled) colors.errorContainer else colors.surfaceContainerHighest)
            .semantics {
                contentDescription = label
                if (enabled) {
                    onLongClick(label) {
                        confirm()
                        true
                    }
                }
            }
            .then(if (enabled) Modifier.hand() else Modifier)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    val filling = scope.launch {
                        progress.animateTo(1f, tween(HOLD_MILLIS, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        confirm()
                    }
                    waitForUpOrCancellation()
                    if (progress.value < 1f) {
                        filling.cancel()
                        scope.launch { progress.animateTo(0f, tween(200)) }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value).background(colors.error))
        Text(label, color = if (enabled) colors.onErrorContainer else colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    }
}

private const val HOLD_MILLIS = 1_500
private const val STRIP = 12
private const val MANY = 50
private const val BIG = 1024L * 1024 * 1024
