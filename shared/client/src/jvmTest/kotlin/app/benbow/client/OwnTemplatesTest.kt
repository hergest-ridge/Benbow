package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.board.Board
import app.benbow.core.content.AttachmentBlock
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.Span
import app.benbow.core.content.TemplateSpace
import app.benbow.core.content.TextBlock
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One's own templates (ADR-061): in none of the journal's places nor on the board; what passes on, what doesn't. */
class OwnTemplatesTest {
    private var time = 1_000L
    private val context = TemplateContext("20 октября 2026", "вторник", "Октябрь", "Что сегодня запомнилось?")

    private inner class Device(server: FakeServer, name: String) {
        val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
        val repo = JournalRepository(db, { time++ }, today = { TODAY })
        val engine = SyncEngine(db, { FakeApi(server) }, { time++ })
        val media = MediaStore(Files.createTempDirectory("benbow-templates-$name").toFile(), db, { FakeApi(server) }, { time++ })

        init {
            Settings(db).connection = Connection("https://pc:8765", "AA", "token-$name", server.storeId, server.epoch, "device-$name")
        }
    }

    @Test
    fun aTemplateShowsInNoneOfTheJournalsPlacesNorOnTheBoard() = runTest {
        val repo = Device(FakeServer(), "phone").repo
        val kept = repo.createNote(TODAY, "Дневник: смородина")
        val template = repo.saveAsTemplate("Смородина", JournalRepository.textBody("Шаблон: смородина"), LAST_YEAR)
        val tag = repo.addTag(kept, "дача")!!
        listOf(kept, template).forEach { id ->
            repo.setFavorite(id, true)
            repo.setPinned(id, true)
            repo.addTag(id, "дача")
            repo.setVeilUntil(id, TODAY)
        }
        repo.saveDraft(template, JournalRepository.textBody("Шаблон: смородина, черная"))

        fun List<EntrySummary>.ids() = map { it.id }
        assertEquals(listOf(kept), repo.journal().first().ids())
        assertEquals(emptyList(), repo.entriesForDay(LAST_YEAR).first().ids())
        assertEquals(listOf(kept), repo.pinned().first().ids())
        assertEquals(listOf(kept), repo.favoritesUpTo(TODAY).first().ids())
        assertEquals(emptyList(), repo.withDrafts().first().ids())
        assertEquals(listOf(TODAY), repo.entryDays().first())
        assertEquals(listOf(kept), repo.feedIndex().first().map { it.id })
        assertEquals(listOf(kept), repo.returned(TODAY).first().ids())
        assertEquals(listOf(kept), repo.search(SearchQuery("смородина")).first().ids())
        assertEquals(listOf(kept), repo.search(SearchQuery(tag = tag)).first().ids())
        assertEquals(listOf(1), repo.tags().first().filter { it.name == "дача" }.map { it.uses })
        assertEquals(BoardNotes(), repo.board().first())

        assertEquals(listOf(OwnTemplate(template, "Смородина", "")), repo.ownTemplates().first().map { it.copy(preview = "") })
        assertEquals("Шаблон: смородина, черная", words(repo.templateStart(template, context)!!.body), "a draft open is what it gives")
    }

    @Test
    fun aNoteSavedAsATemplatePassesOnItsLookTagsAndPicturesNeverItsPlace() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val repo = phone.repo
        val note = repo.createNote(TODAY, "Неделя")
        val file = phone.media.import(Random(1).nextBytes(200).inputStream(), "image/jpeg", "A.jpg", null)
        val picture = repo.attach(note, file, "image")
        repo.setField(note, "media_fit:$picture", "fill")
        repo.setField(note, "look_paper", "kraft")
        repo.setField(note, BACKGROUND_FIELD, "blue")
        repo.addTag(note, "неделя")
        repo.setFavorite(note, true)
        repo.setMood(note, 2)
        val body = DocumentCodec.encode(
            Document(listOf(TextBlock("t", style = "h2", children = listOf(Span("Неделя до {{date}}"))), AttachmentBlock("p", picture))),
        )

        val template = repo.saveAsTemplate("Неделя", body, TODAY, from = note)
        val saved = repo.entry(template).first()!!
        assertEquals(TemplateSpace.TEMPLATE, saved.fields[Board.SPACE])
        assertEquals("Неделя", saved.fields[TemplateSpace.NAME])
        val copied = saved.attachments.single()
        assertNotEquals(picture, copied.meta.id, "a link of its own")
        assertEquals(file.sha256, copied.meta.sha256, "to the same file")
        assertEquals(copied.meta.id, refs(saved.body).single())
        assertEquals("fill", saved.fields["media_fit:${copied.meta.id}"])
        assertEquals("kraft", saved.fields["look_paper"])
        assertEquals("blue", saved.fields[BACKGROUND_FIELD])
        assertEquals(listOf("неделя"), saved.tags.map { it.name })
        assertNull(saved.fields[FAVORITE_FIELD])
        assertNull(saved.fields[MOOD_FIELD])
        assertEquals("Неделя до {{date}}", words(saved.body), "placeholders wait until it is used")
        assertEquals(1, repo.entry(note).first()!!.attachments.size, "the note keeps its own")
        assertEquals(listOf(note), repo.library().first().map { it.entryId }, "«Медиатека» lists the picture once, the note's")

        // An entry made from it: the words filled, its own links to the same files, the look and tags; not a template.
        val start = repo.templateStart(template, context)!!
        val made = repo.createEntry(TODAY, body = start.body)
        repo.fillFromTemplate(made, start)
        val entry = repo.entry(made).first()!!
        assertEquals("Неделя до 20 октября 2026", words(entry.body))
        val its = entry.attachments.single()
        assertTrue(its.meta.id != copied.meta.id && its.meta.id != picture)
        assertEquals(file.sha256, its.meta.sha256)
        assertEquals(its.meta.id, refs(entry.body).single())
        assertEquals("fill", entry.fields["media_fit:${its.meta.id}"])
        assertEquals("kraft", entry.fields["look_paper"])
        assertEquals(listOf("неделя"), entry.tags.map { it.name })
        assertFalse(Board.SPACE in entry.fields)
        assertFalse(TemplateSpace.NAME in entry.fields)
        assertEquals(setOf(note, made), repo.journal().first().map { it.id }.toSet())

        // Another device: the template among its templates only, the entry with its picture in the journal.
        phone.engine.sync()
        val desktop = Device(server, "desktop")
        desktop.engine.sync()
        assertEquals(listOf(template), desktop.repo.ownTemplates().first().map { it.id })
        assertEquals(setOf(note, made), desktop.repo.journal().first().map { it.id }.toSet())
        assertEquals(file.sha256, desktop.repo.entry(made).first()!!.attachments.single().meta.sha256)
    }

    @Test
    fun aTemplateInTheTrashLeavesTheChoiceAndComesBack() = runTest {
        val repo = Device(FakeServer(), "phone").repo
        val template = repo.saveAsTemplate("Сон", JournalRepository.textBody("Что снилось"), TODAY)
        repo.renameTemplate(template, "Сны")
        assertEquals(listOf("Сны"), repo.ownTemplates().first().map { it.name })

        repo.moveToTrash(template)
        assertEquals(emptyList(), repo.ownTemplates().first())
        assertEquals(listOf("Сны"), repo.templateTrash().first().map { it.name })
        assertEquals(emptyList(), repo.trash().first(), "not among the journal's entries")
        repo.restore(template)
        assertEquals(listOf("Сны"), repo.ownTemplates().first().map { it.name })
        assertEquals(emptyList(), repo.templateTrash().first())
    }

    @Test
    fun aStandardTemplateCopiedKeepsItsPlaceholdersUntilUsed() = runTest {
        val repo = Device(FakeServer(), "phone").repo
        val template = repo.saveAsTemplate("Моя неделя", Templates.render(Templates.byId("week")!!, Templates.KEEP), TODAY)
        assertEquals("Неделя до {{date}}", words(repo.templateBody(template)!!).substringBefore('\n'))
        assertEquals("Неделя до 20 октября 2026", words(repo.templateStart(template, context)!!.body).substringBefore('\n'))
        assertNull(repo.templateStart(repo.createNote(TODAY, "Не шаблон"), context), "only a template starts an entry")
    }

    private fun refs(body: String) = DocumentCodec.decode(body).blocks.filterIsInstance<AttachmentBlock>().map { it.ref }

    private fun words(body: String) = DocumentCodec.decode(body).blocks.filterIsInstance<TextBlock>().joinToString("\n") { block ->
        block.children.joinToString("") { it.text }
    }

    companion object {
        const val TODAY = "2026-10-20"
        const val LAST_YEAR = "2025-10-20"
    }
}
