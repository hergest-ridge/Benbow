package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.comment_hint
import app.benbow.app.resources.comments
import app.benbow.app.resources.comments_empty
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.toPlainText
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** An entry's thread: the entry on top, replies oldest first, a reply box at the bottom (retrospective notes, ux.md#psychology). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommentsScreen(services: AppServices, nav: Navigator, route: Route.Comments) {
    val parent by services.repo.entry(route.entryId).collectAsStateWithLifecycle(null)
    val comments by services.repo.comments(route.entryId).collectAsStateWithLifecycle(null)
    Scaffold(
        topBar = { TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.comments)) }) },
        bottomBar = {
            QuickNoteBar(
                onSend = { text -> services.scope.launch { services.repo.createComment(route.entryId, services.today(), text) } },
                hint = stringResource(Res.string.comment_hint),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            parent?.let { entry ->
                item(key = "parent") {
                    Surface(
                        onClick = { nav.open(Route.Editor(entry.id, entry.day)) },
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            dayLabel(entry.day) + " · " + plainPreview(entry.body),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
            val list = comments ?: return@LazyColumn
            if (list.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(32.dp)) {
                        Text(stringResource(Res.string.comments_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(list, key = { it.summary.id }) { comment ->
                Surface(
                    onClick = { nav.open(Route.Editor(comment.summary.id, comment.summary.day)) },
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (comment.text.isNotBlank()) Text(comment.text, style = MaterialTheme.typography.bodyLarge)
                        CommentMedia(services, comment) { nav.open(Route.Media(comment.summary.id, it)) }
                        Text(
                            dateTimeLabel(comment.summary.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The first lines of a Document as plain text, for headers and share sheets. */
internal fun plainPreview(body: String, max: Int = 300): String =
    runCatching { DocumentCodec.decode(body).toPlainText(includeSpoilers = false) }.getOrDefault("").trim().take(max)
