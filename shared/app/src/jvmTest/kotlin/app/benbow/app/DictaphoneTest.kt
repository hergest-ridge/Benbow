package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import app.benbow.core.text.Atom
import app.benbow.core.text.BlockKeys
import app.benbow.core.text.Mark
import app.benbow.core.text.Paragraph
import app.benbow.core.text.RichText
import app.benbow.ui.editor.EditorState
import app.benbow.ui.theme.BenbowTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The dictaphone's keys (7.10b), dictation into the editor (7.10c) and «Заменить текстом» (7.10e). */
class DictaphoneTest {
    private val log = ArrayList<String>()
    private var opens = true
    private val keys = RecorderKeys(
        start = { mode ->
            log += "start $mode"
            opens
        },
        finish = { mode, keep -> log += if (keep) "keep $mode" else "drop $mode" },
    )

    @Test
    fun aTapLatchesAndTheNextTapKeeps() {
        keys.press(RecorderMode.Text)
        keys.release(RecorderMode.Text, heldMs = 120)
        assertEquals(RecorderMode.Text, keys.down)
        assertTrue(keys.latched)
        keys.press(RecorderMode.Text)
        keys.release(RecorderMode.Text, heldMs = 90)
        assertNull(keys.down)
        assertEquals(listOf("start Text", "keep Text"), log)
    }

    @Test
    fun holdingRecordsWhileHeld() {
        keys.press(RecorderMode.Voice)
        assertEquals(RecorderMode.Voice, keys.down)
        keys.release(RecorderMode.Voice, heldMs = 2_400)
        assertNull(keys.down)
        assertEquals(listOf("start Voice", "keep Voice"), log)
    }

    @Test
    fun theOtherKeyLetsTheFirstUpWithItsTakeKept() {
        keys.press(RecorderMode.Voice)
        keys.release(RecorderMode.Voice, heldMs = 100)
        keys.press(RecorderMode.Text)
        keys.release(RecorderMode.Text, heldMs = 100)
        assertEquals(RecorderMode.Text, keys.down)
        assertEquals(listOf("start Voice", "keep Voice", "start Text"), log)
    }

    @Test
    fun crossThrowsAwayAndAKeyThatCantStartStaysUp() {
        keys.press(RecorderMode.Voice)
        keys.release(RecorderMode.Voice, heldMs = 100)
        keys.up(keep = false)
        assertEquals(listOf("start Voice", "drop Voice"), log)
        opens = false
        keys.press(RecorderMode.Text)
        keys.release(RecorderMode.Text, heldMs = 100)
        assertNull(keys.down)
        keys.ended(RecorderMode.Text)
        assertEquals(listOf("start Voice", "drop Voice", "start Text"), log)
    }

    @Test
    fun dictatedPhrasesGoInAsTypedTextOneUndoEach() {
        val editor = EditorState(text("Когда мы приехали на дачу"), blockKeys)
        editor.textFieldState.edit { selection = TextRange(length) }
        editor.insertDictated("Оказалось, что ключи остались дома.", atEnd = false)
        editor.insertDictated("Пришлось ждать соседа.", atEnd = false)
        assertEquals("Когда мы приехали на дачу оказалось, что ключи остались дома. Пришлось ждать соседа.", editor.model.text)
        editor.undo()
        assertEquals("Когда мы приехали на дачу оказалось, что ключи остались дома.", editor.model.text)
    }

    @Test
    fun anEmptyNoteStartsWithASentenceAndASpoilerStaysOne() {
        val editor = EditorState(text(""), blockKeys)
        editor.toggleMark(Mark.Spoiler)
        editor.insertDictated("Тихо.", atEnd = false)
        assertEquals("Тихо.", editor.model.text)
        assertTrue(editor.model.hasMark(0, editor.model.text.length, Mark.Spoiler))
        val blank = EditorState(text(""), blockKeys)
        blank.insertDictated("Сегодня шёл дождь.", atEnd = true)
        assertEquals("Сегодня шёл дождь.", blank.model.text)
    }

    @Test
    fun aVoiceNoteGivesWayToItsWordsAndComesBackOnUndo() {
        val keys = BlockKeys()
        val editor = EditorState(RichText("До", listOf(Paragraph(keys.next()))), keys)
        editor.textFieldState.edit { selection = TextRange(length) }
        editor.insertAtom(Atom.Attachment("voice-1"))
        assertTrue(editor.replaceAttachmentWithText("voice-1", " Шли вдоль берега. "))
        assertEquals(null, editor.model.paragraphs.firstNotNullOfOrNull { it.atom })
        assertTrue("Шли вдоль берега." in editor.model.text)
        editor.undo()
        assertEquals(Atom.Attachment("voice-1"), editor.model.paragraphs.firstNotNullOfOrNull { it.atom })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun keysInBothThemes() {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(width = 420, height = 260) {
                setContent { BenbowTheme(darkTheme = dark) { Keys() } }
                waitForIdle()
                val file = File("build/screens/dictaphone-${if (dark) "dark" else "light"}.png").apply { parentFile.mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            }
        }
    }

    /** The media row at rest, dictating, and recording a voice note with the текст key locked. */
    @androidx.compose.runtime.Composable
    private fun Keys() = Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({}) { Icon(Icons.Gallery, null) }
                IconButton({}) { Icon(Icons.Camera, null) }
                IconButton({}) { Icon(Icons.Brush, null) }
                Row(Modifier.weight(1f)) {}
                KeyHousing { keyPair(voiceDown = false, textDown = false, locked = false) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordingStrip("Текст", TextLamp, 12_000, 0.6f, pending = 1, onDiscard = {}, modifier = Modifier.weight(1f))
                KeyHousing { keyPair(voiceDown = false, textDown = true, locked = false) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordingStrip("Голос", VoiceLamp, 3_000, 0.3f, pending = 0, onDiscard = {}, modifier = Modifier.weight(1f))
                KeyHousing { keyPair(voiceDown = true, textDown = false, locked = true) }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun androidx.compose.foundation.layout.RowScope.keyPair(voiceDown: Boolean, textDown: Boolean, locked: Boolean) {
        RecorderKey(Icons.Mic, "голос", "Голосовое", VoiceLamp, voiceDown, { 0.8f }, {}, {})
        RecorderKey(Icons.Transcribe, "текст", "Текст голосом", TextLamp, textDown, { 0.8f }, {}, {}, locked = locked)
    }

    private val blockKeys = BlockKeys()

    private fun text(value: String): RichText = RichText(value, listOf(Paragraph(blockKeys.next())))
}
