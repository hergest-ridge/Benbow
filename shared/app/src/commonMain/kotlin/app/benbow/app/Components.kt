package app.benbow.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.back
import app.benbow.app.resources.conflict_badge
import app.benbow.app.resources.sync_attention
import app.benbow.app.resources.sync_done
import app.benbow.app.resources.sync_incompatible
import app.benbow.app.resources.sync_not_paired
import app.benbow.app.resources.sync_offline
import app.benbow.app.resources.sync_syncing
import app.benbow.app.resources.sync_waiting
import app.benbow.app.resources.sync_wrong_server
import app.benbow.app.resources.untitled
import app.benbow.client.EntrySummary
import app.benbow.client.Media
import app.benbow.client.SyncResult
import app.benbow.client.SyncStatus
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BackButton(onClick: () -> Unit) {
    IconButton(onClick) { Icon(Icons.Back, stringResource(Res.string.back)) }
}

/** A journal entry in a list: time, title, a few lines of preview. */
@Composable
internal fun EntryCard(
    entry: EntrySummary,
    onClick: () -> Unit,
    media: Media? = null,
    showDay: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = swatch(entry.background) ?: colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val time = if (showDay) "${dayLabel(entry.day)}, ${timeLabel(entry.createdAt)}" else timeLabel(entry.createdAt)
                    Text(time, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    if (entry.favorite) Text("  ★", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    moodEmoji(entry.mood)?.let { Text("  $it", style = MaterialTheme.typography.labelMedium) }
                    if (entry.conflict) {
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.conflict_badge), style = MaterialTheme.typography.labelMedium, color = colors.error)
                    }
                }
                if (entry.title.isNotBlank()) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (entry.preview.isNotBlank()) {
                    Text(
                        entry.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.tags.isNotEmpty()) TagRibbon(entry.tags, Modifier.padding(top = 4.dp))
                if (entry.title.isBlank() && entry.preview.isBlank()) {
                    Text(
                        stringResource(Res.string.untitled),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (media != null) entry.cover?.let { CoverThumb(media, it, entry.mediaCount) }
            trailing?.invoke()
        }
    }
}

/** A calm notice above content with one action. */
@Composable
internal fun Banner(text: String, action: String, error: Boolean = false, onAction: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (error) colors.errorContainer else colors.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            TextButton(onAction) { Text(action) }
        }
    }
}

/** Sync state in a few words with an icon; red when the user has to act, quiet otherwise. */
internal data class SyncLook(val label: String, val tone: Color, val icon: ImageVector)

/** Null when there is nothing to say yet. */
@Composable
internal fun syncLook(status: SyncStatus): SyncLook? {
    val colors = MaterialTheme.colorScheme
    val waiting = (status.pending + status.pendingMedia).toInt()
    return when {
        status.syncing -> SyncLook(stringResource(Res.string.sync_syncing), colors.outline, Icons.Sync)

        status.last is SyncResult.WrongServer -> SyncLook(stringResource(Res.string.sync_wrong_server), colors.error, Icons.Error)

        status.last is SyncResult.Incompatible -> SyncLook(stringResource(Res.string.sync_incompatible), colors.error, Icons.Error)

        status.rejected > 0 -> SyncLook(stringResource(Res.string.sync_attention), colors.error, Icons.Error)

        status.last is SyncResult.NotPaired -> SyncLook(stringResource(Res.string.sync_not_paired), colors.outline, Icons.CloudOff)

        status.last is SyncResult.Failed && waiting > 0 ->
            SyncLook(pluralStringResource(Res.plurals.sync_waiting, waiting, waiting), colors.tertiary, Icons.CloudOff)

        status.last is SyncResult.Failed -> SyncLook(stringResource(Res.string.sync_offline), colors.tertiary, Icons.CloudOff)

        waiting > 0 -> SyncLook(pluralStringResource(Res.plurals.sync_waiting, waiting, waiting), colors.outline, Icons.Cloud)

        status.last is SyncResult.Done -> SyncLook(stringResource(Res.string.sync_done), colors.primary, Icons.CloudDone)

        else -> null
    }
}

/** One icon in a top bar; the words are its description and live on the server screen. Turns while syncing. */
@Composable
internal fun SyncBadge(status: SyncStatus, onClick: () -> Unit) {
    val look = syncLook(status) ?: return
    val turn = if (status.syncing) {
        val spin by rememberInfiniteTransition().animateFloat(0f, -360f, infiniteRepeatable(tween(1_200, easing = LinearEasing)))
        spin
    } else {
        0f
    }
    IconButton(onClick) { Icon(look.icon, look.label, tint = look.tone, modifier = Modifier.rotate(turn)) }
}
