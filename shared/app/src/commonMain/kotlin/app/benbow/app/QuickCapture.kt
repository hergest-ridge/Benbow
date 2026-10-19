package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.capture_files
import app.benbow.app.resources.capture_hint
import app.benbow.app.resources.capture_save
import app.benbow.app.resources.capture_saving
import app.benbow.app.resources.capture_title
import app.benbow.app.resources.dictation_kept
import app.benbow.app.resources.dictation_needs_model
import app.benbow.app.resources.media_mic_denied
import app.benbow.client.ByteSource
import app.benbow.client.IoDispatcher
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What the capture sheet starts with: text and files another app shared, or nothing (the tile, a shortcut). */
class SharedContent internal constructor(
    val text: String,
    internal val files: List<SharedFile>,
    /** The shared page's title (Android's EXTRA_SUBJECT): a shared link's card takes it (ADR-038). */
    internal val linkTitle: String? = null,
) {
    val isEmpty: Boolean get() = text.isBlank() && files.isEmpty()

    companion object {
        val Empty = SharedContent("", emptyList())

        fun text(text: String) = SharedContent(text, emptyList())
    }
}

/** A shared file: read only when saved, copied into the app's media folder before the sheet closes. */
class SharedFile internal constructor(
    internal val mime: String,
    /** What Coil can show as a thumbnail (a content URI on Android), null for no picture. */
    internal val thumbnail: Any?,
    internal val open: () -> ByteSource,
    internal val probe: () -> MediaProbe,
)

/** A link shared from a browser comes as a page title (subject) and the URL (text); keep both, title first. */
fun sharedNoteText(subject: String?, text: String?): String {
    val body = text?.trim().orEmpty()
    val title = subject?.trim().orEmpty()
    return when {
        title.isEmpty() || body.contains(title) -> body
        body.isEmpty() -> title
        else -> "$title\n$body"
    }
}

/**
 * The quick capture sheet over whatever is on screen (share target, Quick Settings tile, launcher shortcut):
 * a line of text and the shared files, saved as a note in one tap. Files are copied in before it closes,
 * while the sharing app's permission to read them still holds.
 */
@Composable
fun QuickCapture(services: AppServices, shared: SharedContent, onClose: () -> Unit) {
    AppTheme(services) {
        var text by rememberSaveable { mutableStateOf(shared.text) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val dictation = rememberFieldDictation({ text }) { text = it }
        // The sheet has no snackbar of its own: what dictation has to say shows under the field.
        var notice by remember { mutableStateOf<StringResource?>(null) }
        LaunchedEffect(Unit) {
            services.messages.collect {
                notice = when (it) {
                    AppMessage.DictationNeedsModel -> Res.string.dictation_needs_model
                    AppMessage.DictationKept -> Res.string.dictation_kept
                    AppMessage.MicDenied -> Res.string.media_mic_denied
                    else -> notice
                }
            }
        }
        val save: () -> Unit = {
            saving = true
            error = null
            scope.launch {
                val failures = mutableListOf<String>()
                withContext(IoDispatcher) {
                    val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                        services.messages.collect { if (it is AppMessage.AttachFailed) failures += it.reason }
                    }
                    val id = services.repo.createNote(services.today(), text.trim(), shared.linkTitle)
                    services.readCardsOf(id)
                    shared.files.map { file -> services.attach({ id }, { file.open() }, probe = { file.probe() }) }.joinAll()
                    listener.cancel()
                }
                if (failures.isEmpty()) onClose() else error = failures.joinToString("\n")
                saving = false
            }
        }
        val quiet = remember { MutableInteractionSource() }
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                .hand().clickable(quiet, indication = null) { if (!saving) onClose() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth().hand().clickable(quiet, indication = null) {},
            ) {
                Column(
                    Modifier.navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(Res.string.capture_title), style = MaterialTheme.typography.titleMedium)
                    if (shared.files.isNotEmpty()) SharedFiles(shared.files)
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(stringResource(Res.string.capture_hint)) },
                        minLines = 2,
                        maxLines = 8,
                        enabled = !saving,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    notice?.let {
                        Text(
                            stringResource(it),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DictationKey(services, dictation, Modifier.padding(end = 8.dp))
                        TextButton(onClose, enabled = !saving, modifier = Modifier.hand(!saving)) {
                            Text(stringResource(Res.string.cancel))
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            save,
                            enabled = !saving && (text.isNotBlank() || shared.files.isNotEmpty()),
                            modifier = Modifier.hand(!saving && (text.isNotBlank() || shared.files.isNotEmpty())),
                        ) {
                            Text(stringResource(if (saving) Res.string.capture_saving else Res.string.capture_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedFiles(files: List<SharedFile>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files) { file ->
                val shape = RoundedCornerShape(12.dp)
                if (file.thumbnail != null && kindOf(file.mime) == "image") {
                    AsyncImage(file.thumbnail, null, Modifier.size(72.dp).clip(shape), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.size(72.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), Alignment.Center) {
                        Text(
                            file.mime.substringAfter('/').take(8),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            pluralStringResource(Res.plurals.capture_files, files.size, files.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
