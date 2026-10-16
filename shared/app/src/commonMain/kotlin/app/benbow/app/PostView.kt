package app.benbow.app

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.comments
import app.benbow.app.resources.comments_count
import app.benbow.app.resources.comments_empty
import app.benbow.app.resources.reaction_add
import app.benbow.client.Comment
import app.benbow.client.EntryDetail
import app.benbow.client.placedIn
import app.benbow.ui.editor.AtomViews
import app.benbow.ui.editor.BenbowEditor
import app.benbow.ui.editor.EditorState
import kotlinx.coroutines.launch
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
    onOpenCommentMedia: (commentId: String, attachmentId: String) -> Unit,
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
                val open = LocalOpenTag.current
                detail.tags.forEach { tag -> TagChip(tag, onClick = open?.let { { it(tag.id) } }) }
            }
        }
        if (ribbon) {
            // Pictures that aren't in the text, laid out as chosen in "Оформление"; with all of them placed, the clip shows all.
            val placed = placedIn(detail.body).toSet()
            val loose = detail.attachments.filter { it.meta.id !in placed }.ifEmpty { detail.attachments }
            val layout = services.appearance.collectAsStateWithLifecycle().value.postMedia
            PostMedia(services, loose, layout, entryId = detail.id, transcripts = detail.transcripts, onRename = { id, caption ->
                services.scope.launch { services.repo.setCaption(detail.id, id, caption) }
            }, onOpen = {
                onOpenMedia(it.meta.id)
            }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).hand(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CommentText(comment)
                    CommentMedia(services, comment) { onOpenCommentMedia(comment.summary.id, it) }
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
            }, color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.hand()) {
                Text(emoji, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Box {
            val add = stringResource(Res.string.reaction_add)
            Surface(
                onClick = { picking = true },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.semantics { contentDescription = add }.hand(),
            ) {
                Icon(
                    Icons.AddReaction,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).size(18.dp),
                )
            }
            DropdownMenu(picking, { picking = false }) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    REACTIONS.forEach { emoji ->
                        DropdownMenuItem(text = { Text(emoji, style = MaterialTheme.typography.titleLarge) }, onClick = {
                            picking = false
                            onReact(emoji, emoji !in chosen)
                        }, modifier = Modifier.size(width = 52.dp, height = 48.dp).hand())
                    }
                }
            }
        }
    }
}

/** A comment's pictures and files, each a third of the screen wide; a tap opens the viewer over the comment's own. */
@Composable
internal fun CommentMedia(services: AppServices, comment: Comment, onOpen: (attachmentId: String) -> Unit) {
    if (comment.media.isEmpty()) return
    val third = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.width / 3).toDp() }
    val (sounds, rest) = comment.media.partition { it.meta.kind == "voice" || it.meta.kind == "audio" }
    sounds.forEach { sound ->
        VoiceRow(services, comment.summary.id, sound, comment.transcripts[sound.meta.id], onRename = { caption ->
            services.scope.launch { services.repo.setCaption(comment.summary.id, sound.meta.id, caption) }
        })
    }
    if (rest.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        rest.forEach { attachment -> MediaThumb(services.media, attachment, third) { onOpen(attachment.meta.id) } }
    }
}
