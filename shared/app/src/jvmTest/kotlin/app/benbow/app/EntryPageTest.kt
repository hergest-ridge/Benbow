package app.benbow.app

import app.benbow.client.addTag
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.benbow.client.setTagCategory
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntryPageTest {
    private val services = AppServices(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
        mediaIn(Files.createTempDirectory("benbow-media").toFile()),
        "desktop",
        "Test PC",
    )

    @Test
    fun anEntryPrintsWithItsTagsPicturesAndThread() = runTest {
        val repo = services.repo
        val id = repo.createNote("2026-10-13", "Прогулка у моря\nтёплый <вечер>")
        repo.setMood(id, 2)
        repo.setTagCategory(repo.addTag(id, "Море")!!, "места")
        val photo = services.media.import(Random(5).nextBytes(500).inputStream(), "image/jpeg", "IMG_1.jpg", null)
        repo.attach(id, photo, "image")
        repo.createComment(id, "2026-10-14", "Перечитал утром")

        val page = services.entryPage(id)!!
        assertEquals("Прогулка у моря", page.title)
        val html = page.html
        java.io.File("build/screens/26-entry-page.html").apply { parentFile.mkdirs() }.writeText(html) // for review, like the screenshots
        assertTrue("<p class=\"meta\">Вторник, 13 октября 2026, " in html && "· 😄" in html, html)
        assertTrue("<span style=\"background:#e2f6d3\">Море</span>" in html, "tags in their (light) category color")
        assertTrue("тёплый &lt;вечер&gt;" in html, "text is escaped")
        assertTrue("<img src=\"file:" in html, "the picture from the device's media folder")
        assertTrue("<h1>Прогулка у моря</h1>" in html, "the first line reads as the title")
        assertTrue("<h3>Комментарии</h3>" in html && "14 октября 2026, " in html && "Перечитал утром" in html, html)
    }
}
