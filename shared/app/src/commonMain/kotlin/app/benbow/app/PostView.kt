package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.comments
import app.benbow.app.resources.comments_count
import app.benbow.app.resources.comments_empty
import app.benbow.app.resources.reaction_add
import app.benbow.client.Comment
import app.benbow.client.EntryDetail
import app.benbow.ui.editor.AtomViews
import app.benbow.ui.editor.BenbowEditor
import app.benbow.ui.editor.EditorState
import org.jetbrains.compose.resources.stringResource

/**
 * An entry read like a post: mood and tags, pictures, the text, reactions, then its thread right under it.
 * One scroll: a long entry reads full screen and the comments follow; a short one shows them at once.
 */
@Composable
internal fun PostView(
    services: AppServices,
    detail: EntryDetail,
    editor: EditorState,
    comments: List<Comment>?,
    onOpenMedia: (String) -> Unit,
    onOpenComment: (Comment) -> Unit,
    onReact: (String, Boolean) -> Unit,
    ribbon: Boolean,
    pictures: AtomViews,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        if (detail.mood != null || detail.tags.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                moodEmoji(detail.mood)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                detail.tags.forEach { TagChip(it) }
            }
        }
        if (ribbon) AttachmentStrip(services.media, detail.attachments) { onOpenMedia(it.meta.id) }
        BenbowEditor(
            state = editor,
            readOnly = true,
            atoms = pictures,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        )
        Reactions(detail.reactions, onReact, Modifier.padding(horizontal = 16.dp))
        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        val count = comments?.size ?: 0
        Text(
            if (count > 0) stringResource(Res.string.comments_count, count) else stringResource(Res.string.comments),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
        )
        if (comments?.isEmpty() == true) {
            Text(
                stringResource(Res.string.comments_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        comments.orEmpty().forEach { comment ->
            Surface(
                onClick = { onOpenComment(comment) },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(comment.text, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        dateTimeLabel(comment.summary.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Box(Modifier.padding(bottom = 16.dp))
    }
}

/** Chosen reactions (tap to take one back) and a button for the rest. */
@Composable
private fun Reactions(chosen: Set<String>, onReact: (String, Boolean) -> Unit, modifier: Modifier = Modifier) {
    var picking by remember { mutableStateOf(false) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        chosen.sorted().forEach { emoji ->
            Surface(onClick = {
                onReact(emoji, false)
            }, color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                Text(emoji, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Box {
            val add = stringResource(Res.string.reaction_add)
            Surface(
                onClick = { picking = true },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.semantics { contentDescription = add },
            ) {
                Text(
                    "☺ +",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            DropdownMenu(picking, { picking = false }) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    REACTIONS.forEach { emoji ->
                        DropdownMenuItem(text = { Text(emoji, style = MaterialTheme.typography.titleLarge) }, onClick = {
                            picking = false
                            onReact(emoji, emoji !in chosen)
                        }, modifier = Modifier.size(width = 52.dp, height = 48.dp))
                    }
                }
            }
        }
    }
}
