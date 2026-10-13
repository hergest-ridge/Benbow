package app.benbow.app

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import app.benbow.client.MediaStore
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
    media: MediaStore? = null,
    showDay: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerLow,
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
                if (entry.tags.isNotEmpty()) {
                    Text(
                        entry.tags.joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

/** Sync state in a few words; red when the user has to act, quiet otherwise. Null when there is nothing to say yet. */
@Composable
internal fun syncLabel(status: SyncStatus): Pair<String, Color>? {
    val colors = MaterialTheme.colorScheme
    val waiting = (status.pending + status.pendingMedia).toInt()
    return when {
        status.syncing -> stringResource(Res.string.sync_syncing) to colors.outline

        status.last is SyncResult.WrongServer -> stringResource(Res.string.sync_wrong_server) to colors.error

        status.last is SyncResult.Incompatible -> stringResource(Res.string.sync_incompatible) to colors.error

        status.rejected > 0 -> stringResource(Res.string.sync_attention) to colors.error

        status.last is SyncResult.NotPaired -> stringResource(Res.string.sync_not_paired) to colors.outline

        status.last is SyncResult.Failed && waiting > 0 -> pluralStringResource(Res.plurals.sync_waiting, waiting, waiting) to
            colors.tertiary

        status.last is SyncResult.Failed -> stringResource(Res.string.sync_offline) to colors.tertiary

        waiting > 0 -> pluralStringResource(Res.plurals.sync_waiting, waiting, waiting) to colors.outline

        status.last is SyncResult.Done -> stringResource(Res.string.sync_done) to colors.primary

        else -> null
    }
}

@Composable
internal fun SyncChip(status: SyncStatus, onClick: () -> Unit) {
    val (label, tone) = syncLabel(status) ?: return
    TextButton(onClick, shape = CircleShape) {
        Box(Modifier.size(8.dp).background(tone, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
