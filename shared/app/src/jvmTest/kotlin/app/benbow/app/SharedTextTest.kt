package app.benbow.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedTextTest {
    @Test
    fun aLinkKeepsItsTitleFirst() {
        assertEquals("Статья про сон\nhttps://example.com/sleep", sharedNoteText("Статья про сон", "https://example.com/sleep"))
    }

    @Test
    fun nothingIsRepeatedOrLost() {
        assertEquals("Смотри: Статья про сон https://e.com", sharedNoteText("Статья про сон", "Смотри: Статья про сон https://e.com"))
        assertEquals("Только тема", sharedNoteText(" Только тема ", null))
        assertEquals("Просто текст", sharedNoteText(null, "Просто текст\n"))
        assertEquals("", sharedNoteText(null, null))
    }
}
