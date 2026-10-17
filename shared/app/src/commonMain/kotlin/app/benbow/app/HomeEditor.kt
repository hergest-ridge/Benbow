package app.benbow.app

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.block_checkin
import app.benbow.app.resources.block_checkin_about
import app.benbow.app.resources.block_drafts
import app.benbow.app.resources.block_drafts_about
import app.benbow.app.resources.block_drafts_empty
import app.benbow.app.resources.block_favorite
import app.benbow.app.resources.block_favorite_about
import app.benbow.app.resources.block_favorite_empty
import app.benbow.app.resources.block_on_this_day
import app.benbow.app.resources.block_on_this_day_about
import app.benbow.app.resources.block_on_this_day_empty
import app.benbow.app.resources.block_pinned
import app.benbow.app.resources.block_pinned_about
import app.benbow.app.resources.block_pinned_empty
import app.benbow.app.resources.block_prompt
import app.benbow.app.resources.block_prompt_about
import app.benbow.app.resources.block_stats
import app.benbow.app.resources.block_stats_about
import app.benbow.app.resources.block_stats_empty
import app.benbow.app.resources.block_today
import app.benbow.app.resources.block_today_about
import app.benbow.app.resources.cancel
import app.benbow.app.resources.home_block_add
import app.benbow.app.resources.home_block_down
import app.benbow.app.resources.home_block_empty
import app.benbow.app.resources.home_block_list
import app.benbow.app.resources.home_block_remove
import app.benbow.app.resources.home_block_up
import app.benbow.app.resources.home_editor_hint
import app.benbow.app.resources.home_editor_title
import app.benbow.app.resources.home_removed
import app.benbow.app.resources.home_reset
import app.benbow.app.resources.home_reset_done
import app.benbow.app.resources.tag_picker_done
import app.benbow.app.resources.veil_undo
import app.benbow.client.BlockSize
import app.benbow.client.HomeBlock
import app.benbow.client.HomeBlockType
import app.benbow.client.HomeLayout
import app.benbow.client.HomeLayoutEditor
import app.benbow.client.addable
import app.benbow.client.packHome
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

internal val HomeBlockType.title: StringResource get() = when (this) {
    HomeBlockType.Checkin -> Res.string.block_checkin
    HomeBlockType.Prompt -> Res.string.block_prompt
    HomeBlockType.Pinned -> Res.string.block_pinned
    HomeBlockType.Today -> Res.string.block_today
    HomeBlockType.Drafts -> Res.string.block_drafts
    HomeBlockType.OnThisDay -> Res.string.block_on_this_day
    HomeBlockType.Favorite -> Res.string.block_favorite
    HomeBlockType.Stats -> Res.string.block_stats
}

private val HomeBlockType.about: StringResource get() = when (this) {
    HomeBlockType.Checkin -> Res.string.block_checkin_about
    HomeBlockType.Prompt -> Res.string.block_prompt_about
    HomeBlockType.Pinned -> Res.string.block_pinned_about
    HomeBlockType.Today -> Res.string.block_today_about
    HomeBlockType.Drafts -> Res.string.block_drafts_about
    HomeBlockType.OnThisDay -> Res.string.block_on_this_day_about
    HomeBlockType.Favorite -> Res.string.block_favorite_about
    HomeBlockType.Stats -> Res.string.block_stats_about
}

/** When a block that has nothing to show now (and so isn't on Home) comes up. */
private val HomeBlockType.whenEmpty: StringResource get() = when (this) {
    HomeBlockType.Pinned -> Res.string.block_pinned_empty
    HomeBlockType.Drafts -> Res.string.block_drafts_empty
    HomeBlockType.OnThisDay -> Res.string.block_on_this_day_empty
    HomeBlockType.Favorite -> Res.string.block_favorite_empty
    HomeBlockType.Stats -> Res.string.block_stats_empty
    else -> about
}

/**
 * «Настроить главную» (ADR-034): a schematic of Home's grid. A tap picks a block, then ↑/↓ or «Убрать» (with «Вернуть»);
 * «Добавить блок» lists the blocks Home doesn't have; «Вернуть обычную главную». Every change shows and is saved at
 * once; [home] (Home behind the sheet, live) tells which blocks have nothing to show now.
 */
@Composable
internal fun HomeEditor(services: AppServices, start: HomeLayout, home: HomeState, select: String?, onDismiss: () -> Unit) {
    val editor = remember { HomeLayoutEditor(services.repo, services.scope, start) }
    DisposableEffect(editor) { onDispose { editor.close() } }
    val layout by editor.layout.collectAsState()
    var selected by remember { mutableStateOf(select) }
    var adding by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun offerUndo(text: suspend () -> String, undo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(text(), getString(Res.string.veil_undo), duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }
    PickerFrame(onDismiss) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.home_editor_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onDismiss, modifier = Modifier.hand()) { Text(stringResource(Res.string.tag_picker_done)) }
                }
                Text(
                    stringResource(Res.string.home_editor_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Schematic(layout, home, selected) { selected = it }
                val drawn = layout.blocks.filter { it.blockSize != null }
                drawn.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.let { at ->
                    val block = drawn[at]
                    BlockTools(
                        block,
                        empty = !home.shows(block),
                        canUp = at > 0,
                        canDown = at < drawn.lastIndex,
                        onMove = { editor.move(block.id, it) },
                        onRemove = {
                            val (removed, place) = editor.remove(block.id) ?: return@BlockTools
                            selected = null
                            offerUndo({
                                getString(Res.string.home_removed, block.kind?.let { getString(it.title) }.orEmpty())
                            }) { editor.put(removed, place) }
                        },
                    )
                }
                val addable = layout.addable()
                if (adding && addable.isNotEmpty()) {
                    Catalog(addable, onPick = { kind ->
                        selected = editor.add(kind, after = selected).id
                        adding = false
                    }, onCancel = { adding = false })
                } else if (addable.isNotEmpty()) {
                    OutlinedButton({ adding = true }, modifier = Modifier.fillMaxWidth().hand()) {
                        Text(stringResource(Res.string.home_block_add))
                    }
                }
                if (layout.custom) {
                    TextButton({
                        val before = editor.reset()
                        selected = null
                        offerUndo({ getString(Res.string.home_reset_done) }) { editor.restore(before) }
                    }, modifier = Modifier.align(Alignment.CenterHorizontally).hand()) { Text(stringResource(Res.string.home_reset)) }
                }
                Spacer(Modifier.height(56.dp)) // room for the snackbar over the last button
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Home's rows in miniature, in the same packing; tiles glide to their new places. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Schematic(layout: HomeLayout, home: HomeState, selected: String?, onSelect: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        LookaheadScope {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                packHome(layout.blocks).forEach { row ->
                    key(row.first().id) {
                        Row(Modifier.animateBounds(this@LookaheadScope), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { block ->
                                Tile(
                                    block,
                                    empty = !home.shows(block),
                                    selected = block.id == selected,
                                    onClick = { onSelect(block.id) },
                                    modifier = Modifier.weight((block.blockSize?.span(2) ?: 2).toFloat()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(block: HomeBlock, empty: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val size = block.blockSize
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
        modifier = modifier.height(
            when (size) {
                BlockSize.L -> 88.dp
                BlockSize.List -> 60.dp
                else -> 44.dp
            },
        ).hand(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val title = block.kind?.let { stringResource(it.title) }.orEmpty()
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (empty && !selected) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val notes = listOfNotNull(
                stringResource(Res.string.home_block_list).takeIf { size == BlockSize.List },
                stringResource(Res.string.home_block_empty).takeIf { empty },
            )
            if (notes.isNotEmpty()) {
                Text(notes.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BlockTools(block: HomeBlock, empty: Boolean, canUp: Boolean, canDown: Boolean, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    val kind = block.kind ?: return
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(kind.title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (empty) kind.whenEmpty else kind.about),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ onMove(-1) }, enabled = canUp, modifier = Modifier.hand()) {
                    Icon(Icons.ArrowUp, stringResource(Res.string.home_block_up), Modifier.size(22.dp))
                }
                IconButton({ onMove(1) }, enabled = canDown, modifier = Modifier.hand()) {
                    Icon(Icons.ArrowDown, stringResource(Res.string.home_block_down), Modifier.size(22.dp))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onRemove, modifier = Modifier.hand()) { Text(stringResource(Res.string.home_block_remove)) }
            }
        }
    }
}

/** The blocks Home doesn't have, each with a line on what it is for. */
@Composable
private fun Catalog(kinds: List<HomeBlockType>, onPick: (HomeBlockType) -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.home_block_add), style = MaterialTheme.typography.titleMedium)
        kinds.forEach { kind ->
            Surface(
                onClick = { onPick(kind) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().hand(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(kind.title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(kind.about),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TextButton(onCancel, modifier = Modifier.hand()) { Text(stringResource(Res.string.cancel)) }
    }
}
