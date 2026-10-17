package app.benbow.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.audio_pause
import app.benbow.app.resources.audio_play
import app.benbow.app.resources.player_close
import app.benbow.app.resources.player_next
import app.benbow.app.resources.player_open
import org.jetbrains.compose.resources.stringResource

/** Opens the full player: screens that show their own mini-player (a post on a phone) use it. */
internal val LocalOpenPlayer = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * The mini-player (ADR-035): a thin strip above the tab bar while a track is loaded. Play/pause, the title and artist,
 * then «Следующий» while it plays and ✕ (closing the player) while it's paused. A tap or a swipe up opens the player.
 */
@Composable
internal fun MiniPlayer(services: AppServices, onOpen: () -> Unit, bottomInset: Boolean, modifier: Modifier = Modifier) {
    val state by services.player.state.collectAsState()
    // The last track stays drawn while the strip folds away after ✕.
    var last by remember { mutableStateOf<PlayerState?>(null) }
    state?.let { now -> SideEffect { last = now } }
    AnimatedVisibility(
        visible = state != null,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        modifier = modifier,
    ) {
        (state ?: last)?.let { Strip(services, it, onOpen, bottomInset) }
    }
}

@Composable
private fun Strip(services: AppServices, now: PlayerState, onOpen: () -> Unit, bottomInset: Boolean) {
    val player = services.player
    val colors = MaterialTheme.colorScheme
    val openLabel = stringResource(Res.string.player_open)
    val swipe = with(LocalDensity.current) { SWIPE.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    Surface(
        color = colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().hand().clickable(onClickLabel = openLabel, onClick = onOpen)
            .draggable(
                rememberDraggableState { dragged += it },
                Orientation.Vertical,
                onDragStarted = { dragged = 0f },
                onDragStopped = { velocity -> if (dragged < -swipe || velocity < -FLING) onOpen() },
            ),
    ) {
        // Without a tab bar under it, it reaches down behind the system's navigation bar.
        Box(if (bottomInset) Modifier.navigationBarsPadding() else Modifier) {
            Row(Modifier.height(HEIGHT).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = playLabel(now.playing)
                IconButton(player::playPause, modifier = Modifier.hand().semantics { contentDescription = label }) {
                    PlayGlyph(now.playing, now.loading && !now.playing, colors.onSurface)
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(now.track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitleOf(now.track)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                AnimatedContent(now.playing, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "next or close") { playing ->
                    if (playing) {
                        IconButton(player::next, enabled = now.hasNext, modifier = Modifier.hand()) {
                            Icon(Icons.SkipNext, stringResource(Res.string.player_next))
                        }
                    } else {
                        IconButton(player::close, modifier = Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.player_close)) }
                    }
                }
            }
            // How far it has played, as a hairline along the bottom.
            val total = now.durationMs.takeIf { it > 0 }
            if (total != null) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth((now.positionMs.toFloat() / total).coerceIn(0f, 1f)).height(2.dp)
                        .background(colors.primary),
                )
            }
        }
    }
}

/** The line under a track's title: its artist, or when a voice note was recorded. */
@Composable
internal fun subtitleOf(track: Track): String? = track.artist ?: track.recordedAt?.takeIf { track.voice }?.let { dateTimeLabel(it) }

/** The words for a play button: what a tap does. */
@Composable
internal fun playLabel(playing: Boolean): String = stringResource(if (playing) Res.string.audio_pause else Res.string.audio_play)

private val HEIGHT = 56.dp
private val SWIPE = 24.dp
private const val FLING = 600f
