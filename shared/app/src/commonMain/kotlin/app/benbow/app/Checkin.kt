package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.checkin_save
import app.benbow.app.resources.checkin_title
import app.benbow.app.resources.checkin_words_hint
import app.benbow.app.resources.kindness_hint
import app.benbow.app.resources.later
import app.benbow.app.resources.write
import app.benbow.client.EMOTION_WORDS
import org.jetbrains.compose.resources.stringResource

/** One tap for the mood; feelings and words only if wanted (affect labeling, ux.md#psychology). */
@Composable
internal fun CheckinCard(onSave: (mood: Int, emotions: List<String>, text: String) -> Unit, modifier: Modifier = Modifier) {
    var mood by remember { mutableStateOf<Int?>(null) }
    var emotions by remember { mutableStateOf(emptySet<String>()) }
    var text by remember { mutableStateOf("") }
    HomeCard(modifier = modifier) {
        Text(stringResource(Res.string.checkin_title), style = MaterialTheme.typography.titleMedium)
        Row {
            MOODS.forEach { (value, face) ->
                val chosen = if (mood == value) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                TextButton({
                    mood = value
                }, colors = ButtonDefaults.textButtonColors(containerColor = chosen), modifier = Modifier.hand()) {
                    Text(face, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        val chosen = mood ?: return@HomeCard
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EMOTION_WORDS.forEach { word ->
                FilterChip(word in emotions, {
                    emotions = if (word in
                        emotions
                    ) {
                        emotions - word
                    } else {
                        emotions + word
                    }
                }, { Text(word) }, modifier = Modifier.hand())
            }
        }
        OutlinedTextField(text, {
            text = it
        }, placeholder = { Text(stringResource(Res.string.checkin_words_hint)) }, modifier = Modifier.fillMaxWidth())
        Button({
            onSave(chosen, emotions.toList(), text)
            mood = null
            emotions = emptySet()
            text = ""
        }, modifier = Modifier.hand()) { Text(stringResource(Res.string.checkin_save)) }
    }
}

/** After a hard check-in: a kinder question, never pushed (self-compassion). */
@Composable
internal fun KindnessCard(question: String, onWrite: () -> Unit, onLater: () -> Unit) {
    HomeCard(color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(stringResource(Res.string.kindness_hint), style = MaterialTheme.typography.bodyMedium)
        Text(question, style = MaterialTheme.typography.titleMedium)
        Row {
            TextButton(onWrite, modifier = Modifier.hand()) { Text(stringResource(Res.string.write)) }
            TextButton(onLater, modifier = Modifier.hand()) { Text(stringResource(Res.string.later)) }
        }
    }
}

/** "Итоги недели" / "Итоги месяца" with a short summary and a template to write it. */
@Composable
internal fun ReviewCard(title: String, summary: String, onWrite: () -> Unit) {
    HomeCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (summary.isNotEmpty()) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onWrite, modifier = Modifier.hand()) { Text(stringResource(Res.string.write)) }
    }
}

@Composable
internal fun HomeCard(
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = color,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
