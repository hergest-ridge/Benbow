package app.benbow.app

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.benbow.client.FeedKey
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The feed over the whole journal (ADR-036): windows of posts, the month in view, «К сегодня». */
@OptIn(ExperimentalTestApi::class)
class FeedNavigationTest {
    private val services = AppServices(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
        mediaIn(java.nio.file.Files.createTempDirectory("benbow-media").toFile()),
        "desktop",
        "Test PC",
    )

    @Test
    fun theFeedReachesThePastAndComesBackToToday() = runDesktopComposeUiTest(width = 412, height = 860) {
        // Two posts a day for 300 days: twice what the feed used to stop at.
        val today = LocalDate.parse(services.today())
        val numbers = (0 until POSTS).associate { i ->
            services.repo.createNote(today.minus(i / 2, DateTimeUnit.DAY).toString(), "Запись ${POSTS - 1 - i}") to POSTS - 1 - i
        }
        val rows = feedRows(runBlocking { services.repo.feedIndex().first() })
        val middle = rows.size / 2
        val there = numbers.getValue((rows.drop(middle).first { it is FeedRow.Item } as FeedRow.Item).key)
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntil(timeoutMillis = 5_000) { shownPosts().contains(POSTS - 1) }
        assertTrue(onAllNodes(hasText("К сегодня")).fetchSemanticsNodes().isEmpty(), "nothing to come back to yet")

        // Deep down, the window follows: posts, not placeholders, the month under the title and «К сегодня».
        onNode(hasScrollToIndexAction()).performScrollToIndex(middle)
        waitUntil(timeoutMillis = 5_000) { shownPosts().contains(there) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("К сегодня")).fetchSemanticsNodes().isNotEmpty() }
        val month = MONTHS[rows[middle].month.drop(5).toInt() - 1]
        assertTrue(onAllNodes(hasText(month, substring = true)).fetchSemanticsNodes().isNotEmpty(), "$month under the title")
        screenshot("80-feed-deep")

        onNodeWithText("К сегодня").click()
        waitUntil(timeoutMillis = 5_000) { shownPosts().contains(POSTS - 1) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("К сегодня")).fetchSemanticsNodes().isEmpty() }

        // The very first post is there too.
        onNode(hasScrollToIndexAction()).performScrollToIndex(rows.lastIndex)
        waitUntil(timeoutMillis = 5_000) { shownPosts().contains(0) }
        screenshot("81-feed-first-post")

        // Back after five minutes away: today again.
        services.cameBack.tryEmit(Unit)
        waitUntil(timeoutMillis = 5_000) { shownPosts().contains(POSTS - 1) }
    }

    @Test
    fun windowsAreChunksAroundTheScreen() {
        val keys = List(130) { FeedKey("p$it", "2026-10-17", it.toLong()) }
        assertEquals(0..1, chunksAround(0, 5))
        assertEquals(1..4, chunksAround(85, 120), "the chunk before the screen's first post to the one after its last")
        assertEquals(keys.take(80).map { it.id }, feedWindow(keys, listOf(0..1)))
        assertEquals(keys.drop(120).map { it.id }, feedWindow(keys, listOf(3..4)), "it stops at the end")
        assertEquals(
            keys.take(40).map {
                it.id
            } + keys.drop(80).take(40).map { it.id },
            feedWindow(keys, listOf(2..2, 0..0)),
            "in feed order",
        )
    }

    @Test
    fun rowsKeepTheirPlacesWithPlaceholdersForPostsNotRead() {
        val keys = listOf(
            FeedKey("a", "2026-10-17", 3),
            FeedKey("b", "2026-10-17", 2),
            FeedKey("c", "2026-09-30", 1, media = true),
        )
        val rows = feedRows(keys)
        assertEquals(listOf("month:2026-10", "day:2026-10-17", "a", "b", "month:2026-09", "day:2026-09-30", "c"), rows.map { it.key })
        assertEquals(listOf(0, 1, 2), rows.filterIsInstance<FeedRow.Item>().map { it.pos })
        assertTrue(rows.filterIsInstance<FeedRow.Item>().all { it.item == null && it.contentType == 4 })
        assertEquals(0, rows.postNear(0), "a title: the post below it")
        assertEquals(2, rows.postNear(6))
        assertEquals(1, rows.postNear(3))
        assertEquals(2, rows.postNear(4))
        assertNull(emptyList<FeedRow>().postNear(0))
    }

    /** The numbers of the posts laid out on screen. */
    private fun ComposeUiTest.shownPosts(): Set<Int> = onAllNodes(hasText("Запись ", substring = true)).fetchSemanticsNodes()
        .filter { it.isShown() }
        .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        .mapNotNull { it.removePrefix("Запись ").toIntOrNull() }.toSet()

    private fun SemanticsNodeInteractionsProvider.screenshot(name: String) {
        val file = File("build/screens/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }

    private fun SemanticsNodeInteraction.click() = performSemanticsAction(SemanticsActions.OnClick)

    private fun SemanticsNode.isShown() = boundsInRoot.let { it.bottom > 0f && it.top < 860f && it.height > 0f }

    private companion object {
        const val POSTS = 600
        val MONTHS = listOf(
            "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
        )
    }
}
