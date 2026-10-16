package app.benbow.client

import app.benbow.client.calendar.CalendarRepository
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.benbow.core.calendar.Ics
import app.benbow.core.calendar.IcsTime
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchiveExportTest {
    private var time = 1_000L
    private val server = FakeServer()
    private val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
    private val repo = JournalRepository(db, { time++ })
    private val media = MediaStore(Files.createTempDirectory("benbow-export").toFile(), db, { FakeApi(server) }, { time++ })
    private val photo = Random(7).nextBytes(4_000)

    init {
        Settings(db).connection = Connection("https://pc:8765", "AA", "token", server.storeId, server.epoch, "device")
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }

    @Test
    fun theWholeJournalReadsWithoutTheApp() = runTest {
        val walk = repo.createNote("2026-10-13", "Прогулка у моря\nтёплый вечер")
        repo.addTag(walk, "Море")
        repo.attach(walk, media.import(photo.inputStream(), "image/jpeg", "IMG_1.jpg", null), "image", 40, 30)
        val lost = repo.createNote("2026-09-02", "Старое фото")
        val gone = media.import(Random(8).nextBytes(100).inputStream(), "image/jpeg", "IMG_2.jpg", null)
        repo.attach(lost, gone, "image")
        File(gone.path).delete() // evicted here and the server can't hand it out either

        val out = ByteArrayOutputStream()
        val report = ArchiveExport(db, media).write(out, withMedia = true, today = "2026-10-13")
        val files = unzip(out.toByteArray())

        assertEquals(ExportReport(entries = 2, files = 1, missing = 1), report)
        val note = files.keys.single { it.startsWith("entries/2026/10/") && it.endsWith(".md") }
        val markdown = files.getValue(note).decodeToString()
        assertTrue(markdown.startsWith("---\nid: \"$walk\""), markdown)
        assertTrue("tags: [\"Море\"]" in markdown)
        assertTrue("- **Море**" in files.getValue("tags.md").decodeToString(), "listed by its name")
        assertTrue("![IMG_1.jpg](<../../../media/2026/10/IMG_1.jpg>)" in markdown, markdown)
        assertContentEquals(photo, files.getValue("media/2026/10/IMG_1.jpg"), "the picture itself, byte for byte")

        val sidecar = Json.parseToJsonElement(files.getValue(note.removeSuffix(".md") + ".json").decodeToString()).jsonObject
        assertEquals(repo.entry(walk).first()!!.body, sidecar.getValue("body").jsonPrimitive.content, "the exact document, lossless")
        val tag = sidecar.getValue("tags").jsonArray.single().jsonObject
        assertEquals(repo.tags().first().single().id, tag.getValue("id").jsonPrimitive.content, "tags by id and name (ADR-032)")
        assertEquals("Море", tag.getValue("name").jsonPrimitive.content)

        val old = files.entries.single { it.key.startsWith("entries/2026/09/") && it.key.endsWith(".md") }.value.decodeToString()
        assertTrue("<!-- image ${gone.sha256} is not in this export" in old, old)
        val readme = files.getValue("README.md").decodeToString()
        assertTrue("- Entries: 2" in readme && "## Not included (1)" in readme && gone.sha256 in readme, readme)
    }

    @Test
    fun withoutFilesTheNotesSaySo() = runTest {
        val walk = repo.createNote("2026-10-13", "Прогулка")
        repo.attach(walk, media.import(photo.inputStream(), "image/jpeg", "IMG_1.jpg", null), "image")
        repo.setTagCategory("море", "места") // the tag registry is app data, not an entry

        val out = ByteArrayOutputStream()
        assertEquals(ExportReport(1, 0, 0), ArchiveExport(db, media).write(out, withMedia = false, today = "2026-10-13"))
        val files = unzip(out.toByteArray())
        assertEquals(setOf("README.md", "tags.json", "tags.md"), files.keys.filter { !it.startsWith("entries/") }.toSet())
        assertTrue(
            "was left out (exported without files)" in
                files.entries.single { it.key.startsWith("entries/") && it.key.endsWith(".md") }.value.decodeToString(),
        )
        // The tags with their categories, to read without the app (7.9 T5).
        val book = files.getValue("tags.md").decodeToString()
        assertTrue("## Места\n\n- **море**\n" in book, book)
        assertTrue("\"category\": \"места\"" in files.getValue("tags.json").decodeToString())
    }

    @Test
    fun calendarsTravelAsIcsFilesAndAJson() = runTest {
        val calendar =
            CalendarRepository(CalendarDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema)), { time++ })
        calendar.createEvent("2026-10-14", "Врач", "09:30")
        val work = calendar.createCalendar("Работа", "storm")
        calendar.createEvent("2026-10-15", "Созвон", null, calendar = work)
        repo.createNote("2026-10-13", "Запись")

        val out = ByteArrayOutputStream()
        ArchiveExport(db, media, calendar).write(out, withMedia = false, today = "2026-10-15")
        val files = unzip(out.toByteArray())

        val mine = Ics.parse(files.getValue("calendars/Мои события.ics").decodeToString()).single()
        assertEquals(listOf("Врач"), mine.events.map { it.summary })
        assertEquals(IcsTime("20261014T093000"), mine.events.single().start)
        assertEquals("Работа", Ics.parse(files.getValue("calendars/Работа.ics").decodeToString()).single().name)
        assertTrue("Созвон" in files.getValue("calendars/calendar.json").decodeToString())
        assertTrue("- Calendars: 2" in files.getValue("README.md").decodeToString())
    }
}
