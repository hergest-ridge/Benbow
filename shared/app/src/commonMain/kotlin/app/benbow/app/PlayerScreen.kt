package app.benbow.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.benbow.app.resources.Res
import app.benbow.app.resources.player_collapse
import app.benbow.app.resources.player_entry
import app.benbow.app.resources.player_next
import app.benbow.app.resources.player_now
import app.benbow.app.resources.player_previous
import app.benbow.app.resources.player_queue
import app.benbow.client.Media
import app.benbow.client.randomRead
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.animateColorScheme
import com.materialkolor.ktx.themeColorOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource

/**
 * The player screen (ADR-035), like a streaming app's: the cover large on its own colors, title, artist and album, the
 * seek bar, previous / play / next, the entry it comes from, and what plays next. A sheet as tall as the screen on a
 * phone (a swipe down or Back closes it); a dialog in a wide window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerScreen(services: AppServices, onOpenEntry: (String) -> Unit, onDismiss: () -> Unit) {
    val state by services.player.state.collectAsState()
    val now = state
    if (now == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val scheme = animateColorScheme(rememberCoverScheme(services.media, now.track.cover))
    MaterialTheme(colorScheme = scheme) {
        if (LocalWide.current) {
            Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(0.9f).fillMaxHeight(0.92f),
                ) { PlayerContent(services, now, onOpenEntry, onDismiss) }
            }
        } else {
            ModalBottomSheet(
                onDismissRequest = onDismiss,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                dragHandle = null,
                containerColor = scheme.surface,
            ) { PlayerContent(services, now, onOpenEntry, onDismiss) }
        }
    }
}

@Composable
private fun PlayerContent(services: AppServices, now: PlayerState, onOpenEntry: (String) -> Unit, onDismiss: () -> Unit) {
    val player = services.player
    val colors = MaterialTheme.colorScheme
    val track = now.track
    val upcoming = now.queue.withIndex().drop(now.index + 1)
    LazyColumn(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to colors.primaryContainer, 0.75f to colors.surface)),
    ) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss, modifier = Modifier.hand()) { Icon(Icons.ExpandMore, stringResource(Res.string.player_collapse)) }
                Text(
                    stringResource(Res.string.player_now),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 48.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        item(key = "cover") { BigCover(services.media, track, now.playing) }
        item(key = "names") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(top = 28.dp)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                listOfNotNull(subtitleOf(track), track.album).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item(key = "seek") { BigSeek(now, player::seek) }
        item(key = "controls") {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(player::previous, modifier = Modifier.size(56.dp).hand()) {
                    Icon(Icons.SkipPrevious, stringResource(Res.string.player_previous), Modifier.size(36.dp))
                }
                val label = playLabel(now.playing)
                Surface(
                    onClick = player::playPause,
                    shape = CircleShape,
                    color = colors.primary,
                    contentColor = colors.onPrimary,
                    modifier = Modifier.size(76.dp).hand().semantics { contentDescription = label },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        PlayGlyph(now.playing, now.loading && !now.playing, colors.onPrimary, 40.dp)
                    }
                }
                IconButton(player::next, enabled = now.hasNext, modifier = Modifier.size(56.dp).hand()) {
                    Icon(Icons.SkipNext, stringResource(Res.string.player_next), Modifier.size(36.dp))
                }
            }
        }
        track.entryId?.let { entry ->
            item(key = "entry") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton({
                        onDismiss()
                        onOpenEntry(entry)
                    }, modifier = Modifier.hand()) { Text(stringResource(Res.string.player_entry)) }
                }
            }
        }
        if (upcoming.isNotEmpty()) {
            item(key = "queue") {
                Text(
                    stringResource(Res.string.player_queue),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
                )
            }
            itemsIndexed(upcoming, key = { _, it -> "q:" + it.value.id }) { _, (index, next) ->
                QueueRow(services.media, next) { player.skipTo(index) }
            }
        }
        item(key = "end") { Spacer(Modifier.navigationBarsPadding().height(24.dp)) }
    }
}

/** The cover as large as the width allows; it sinks back a little while paused. */
@Composable
private fun BigCover(media: Media, track: Track, playing: Boolean) {
    val colors = MaterialTheme.colorScheme
    val scale by animateFloatAsState(if (playing) 1f else 0.9f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "cover")
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(top = 12.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f).scale(scale).shadow(18.dp, shape).clip(shape)
                .background(colors.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (track.voice) Icons.Mic else Icons.MusicNote, null, Modifier.size(96.dp), tint = colors.onSecondaryContainer)
            track.cover?.takeUnless { track.voice }?.let { CoverImage(media, it, Modifier.fillMaxSize()) }
        }
    }
}

/** The seek bar across the screen, the time played on the left and the time left on the right. */
@Composable
private fun BigSeek(now: PlayerState, onSeek: (Long) -> Unit) {
    val total = now.durationMs.takeIf { it > 0 } ?: now.track.durationMs ?: 0
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (total > 0) (now.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp)) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * total).toLong()) }
                dragging = null
            },
            enabled = total > 0,
            modifier = Modifier.fillMaxWidth().hand(),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val played = (fraction * total).toLong()
            Text(durationLabel(played), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (total > 0) {
                Text("-" + durationLabel(total - played), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QueueRow(media: Media, track: Track, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().hand().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(colors.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (track.voice) Icons.Mic else Icons.MusicNote, null, Modifier.size(22.dp), tint = colors.onSecondaryContainer)
            track.cover?.takeUnless { track.voice }?.let { CoverImage(media, it, Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitleOf(track)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        track.durationMs?.let { Text(durationLabel(it), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
    }
}

/** The app's colors made again from the cover's main color (the app's own when there's no cover). */
@Composable
private fun rememberCoverScheme(media: Media, cover: String?): ColorScheme {
    val base = MaterialTheme.colorScheme
    val dark = base.surface.luminance() < 0.5f
    val seeds = remember { mutableStateMapOf<String, Color?>() }
    val seed by produceState(cover?.let { seeds[it] }, cover) {
        value = when {
            cover == null -> null
            cover in seeds -> seeds[cover]
            else -> coverSeed(media, cover).also { seeds[cover] = it }
        }
    }
    return remember(seed, dark, base) { seed?.let { dynamicColorScheme(it, dark) } ?: base }
}

/** The cover's main color, from a small copy of it (the colors are the same, the counting much quicker). */
private suspend fun coverSeed(media: Media, sha256: String): Color? = try {
    val file = media.localFile(sha256) ?: media.file(sha256)
    withContext(Dispatchers.Default) {
        val read = file.randomRead()
        val image = read.read(0, read.size.toInt()).decodeToImageBitmap()
        val small = ImageBitmap(SEED_SIZE, SEED_SIZE)
        Canvas(small).drawImageRect(image, dstSize = IntSize(SEED_SIZE, SEED_SIZE), paint = Paint())
        small.themeColorOrNull()
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

private const val SEED_SIZE = 48
