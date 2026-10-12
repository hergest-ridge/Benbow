package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import app.benbow.core.content.toRichText
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DraftSessionTest {
    private val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
    private var time = 1_000L
    private val repo = JournalRepository(db, { time++ })

    private fun doc(text: String) = Document(listOf(TextBlock("k1", children = listOf(Span(text)))))

    private fun DraftSession.onChange(document: Document) = onChange(document.toRichText())

    private fun TestScope.session(entryId: String?) = DraftSession(repo, backgroundScope, entryId, "2026-10-13", testScheduler.timeSource)

    private fun outbox() = db.benbowQueries.pendingCount().executeAsOne()

    // Read the DB directly: awaiting the repository flows (another dispatcher) would let runTest skip virtual time ahead.
    private fun hasDraft(id: String) = db.benbowQueries.selectDraft(id).executeAsOneOrNull() != null

    private fun shown(id: String) =
        db.benbowQueries.selectDraft(id).executeAsOneOrNull()?.body ?: db.benbowQueries.selectEntry(id).executeAsOne().body

    @Test
    fun newEntryIsCreatedOnlyOnceItHasText() = runTest {
        val session = session(null)
        session.onChange(doc("  "))
        advanceTimeBy(1.seconds)
        assertNull(session.entryId)
        assertTrue(db.benbowQueries.journal().executeAsList().isEmpty(), "an editor opened by mistake leaves nothing behind")

        session.onChange(doc("Привет"))
        advanceTimeBy(500.milliseconds)
        val id = assertNotNull(session.entryId)
        assertEquals(DocumentCodec.encode(doc("Привет")), shown(id))
    }

    @Test
    fun draftIsSavedWithinHalfASecondAndCommittedWhenIdle() = runTest {
        val id = repo.createEntry("2026-10-13")
        val session = session(id)
        session.onChange(doc("a"))
        session.onChange(doc("ab"))
        advanceTimeBy(450.milliseconds)
        runCurrent()
        assertTrue(hasDraft(id))
        assertEquals(DocumentCodec.encode(doc("ab")), shown(id))
        assertEquals(2, outbox(), "a draft is not a revision yet")

        advanceTimeBy(31.seconds)
        assertEquals(false, hasDraft(id))
        assertEquals(3, outbox())
    }

    @Test
    fun continuousTypingCommitsEveryTwoMinutes() = runTest {
        val id = repo.createEntry("2026-10-13")
        val session = session(id)
        repeat(14) {
            session.onChange(doc("x".repeat(it + 1)))
            advanceTimeBy(10.seconds)
        }
        assertEquals(3, outbox(), "one revision after two minutes, while typing goes on")
    }

    @Test
    fun commitFlushesWhatIsNotSavedYet() = runTest {
        val id = repo.createEntry("2026-10-13")
        val session = session(id)
        session.onChange(doc("последнее слово"))
        session.commit()
        runCurrent()
        assertEquals(false, hasDraft(id))
        assertEquals(DocumentCodec.encode(doc("последнее слово")), shown(id))
    }
}
