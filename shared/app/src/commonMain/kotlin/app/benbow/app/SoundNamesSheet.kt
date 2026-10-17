package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.sound_album
import app.benbow.app.resources.sound_artist
import app.benbow.app.resources.sound_names_title
import app.benbow.app.resources.sound_save
import app.benbow.app.resources.sound_title
import app.benbow.app.resources.transcript_remove
import org.jetbrains.compose.resources.stringResource

/** A sound's names given by hand (owner 2026-10-18): the title is its caption, artist and album go to its `track:` field. */
internal data class SoundNames(val title: String?, val artist: String?, val album: String?)

/**
 * Names a voice note or a sound like a track: title, artist, album (a sheet on a phone, a dialog on a wide window). A
 * blank title goes back to [defaultTitle]; a voice note given an artist counts as music (no transcription is offered).
 * With [onRemoveTranscript], a sound that has a transcript can let it go here too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SoundNamesSheet(
    current: SoundNames,
    defaultTitle: String,
    onSave: (SoundNames) -> Unit,
    onDismiss: () -> Unit,
    onRemoveTranscript: (() -> Unit)? = null,
) {
    val content = @Composable {
        var title by remember { mutableStateOf(TextFieldValue(current.title.orEmpty(), TextRange(current.title.orEmpty().length))) }
        var artist by remember { mutableStateOf(current.artist.orEmpty()) }
        var album by remember { mutableStateOf(current.album.orEmpty()) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        val save = {
            onSave(SoundNames(title.text.clean(), artist.clean(), album.clean()))
            onDismiss()
        }
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.sound_names_title), style = MaterialTheme.typography.titleMedium)
            val words = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next)
            OutlinedTextField(
                title,
                { title = it },
                label = { Text(stringResource(Res.string.sound_title)) },
                placeholder = { Text(defaultTitle) },
                singleLine = true,
                keyboardOptions = words,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            OutlinedTextField(
                artist,
                { artist = it },
                label = { Text(stringResource(Res.string.sound_artist)) },
                singleLine = true,
                keyboardOptions = words,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                album,
                { album = it },
                label = { Text(stringResource(Res.string.sound_album)) },
                singleLine = true,
                keyboardOptions = words.copy(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                if (onRemoveTranscript != null) {
                    TextButton({
                        onRemoveTranscript()
                        onDismiss()
                    }, modifier = Modifier.hand()) { Text(stringResource(Res.string.transcript_remove)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onDismiss, modifier = Modifier.hand()) { Text(stringResource(Res.string.cancel)) }
                Button(save, modifier = Modifier.hand()) { Text(stringResource(Res.string.sound_save)) }
            }
        }
    }
    if (LocalWide.current) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 480.dp)) {
                Column(Modifier.padding(top = 20.dp)) { content() }
            }
        }
    } else {
        // Open all the way: half open, the keyboard covered the fields (owner 2026-10-18).
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            content()
        }
    }
}

private fun String.clean(): String? = trim().takeIf { it.isNotEmpty() }
