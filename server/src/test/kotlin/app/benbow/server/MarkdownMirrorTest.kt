package app.benbow.server

import app.benbow.core.board.Board
import app.benbow.core.sync.EntryState
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.LwwValue
import app.benbow.server.backup.MarkdownMirror
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The mirror holds every entry once (ADR-040): moving between the journal and the board moves its files too. */
class MarkdownMirrorTest {
    @Test
    fun aNoteMovingBetweenTheJournalAndTheBoardLeavesNoCopyBehind() {
        val root = Files.createTempDirectory("benbow-mirror")
        val mirror = MarkdownMirror(root)
        fun entry(vararg fields: Pair<String, String>) = EntryState(
            "0199f0aa-7e1c-7000-8000-00000000abcd",
            "note",
            "2026-10-14",
            0,
            fields = fields.associate { (k, v) -> k to LwwValue(v, Hlc.ZERO) },
        )
        fun files(root: Path) =
            Files.walk(root).filter { Files.isRegularFile(it) }.map { root.relativize(it).toString().replace('\\', '/') }.sorted().toList()

        mirror.write(entry(), BODY)
        assertEquals(listOf("2026/10/2026-10-14_00000000abcd.json", "2026/10/2026-10-14_00000000abcd.md"), files(root))

        mirror.write(entry(Board.SPACE to Board.BOARD), BODY)
        assertEquals(listOf("notes/2026-10-14_00000000abcd.json", "notes/2026-10-14_00000000abcd.md"), files(root))
        assertTrue("space: \"board\"" in root.resolve("notes/2026-10-14_00000000abcd.md").readText())

        mirror.write(entry(Board.SPACE to Board.BOARD, Board.ARCHIVED to "true"), BODY)
        assertEquals(listOf("notes/archive/2026-10-14_00000000abcd.json", "notes/archive/2026-10-14_00000000abcd.md"), files(root))

        mirror.write(entry(Board.ARCHIVED to "true"), BODY)
        assertEquals(listOf("2026/10/2026-10-14_00000000abcd.json", "2026/10/2026-10-14_00000000abcd.md"), files(root))
        assertFalse(root.resolve("notes/2026-10-14_00000000abcd.md").exists())
    }

    private companion object {
        const val BODY = """{"v":1,"blocks":[{"_key":"k1","_type":"block","style":"normal",""" +
            """"children":[{"_type":"span","text":"Нитки","marks":[]}],"markDefs":[]}]}"""
    }
}
