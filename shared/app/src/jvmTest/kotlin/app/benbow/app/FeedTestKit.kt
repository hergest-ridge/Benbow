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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performSemanticsAction
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO

// What the feed's screen tests share (ADR-036): a desktop app on in-memory databases, a long feed, and how to read it.

internal fun feedTestServices() = AppServices(
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
    mediaIn(java.nio.file.Files.createTempDirectory("benbow-media").toFile()),
    "desktop",
    "Test PC",
).also {
    // Its start writes to the same in-memory connection (summaries, search): a test's own writes wait for it.
    kotlinx.coroutines.runBlocking { it.started.join() }
}

/** [count] posts, two a day back from today, «Запись N» with the oldest as 0; each post's id to its number. */
internal fun AppServices.seedPosts(count: Int): Map<String, Int> {
    val today = LocalDate.parse(today())
    return (0 until count).associate { i ->
        repo.createNote(today.minus(i / 2, DateTimeUnit.DAY).toString(), "Запись ${count - 1 - i}") to
            count - 1 - i
    }
}

/** The numbers of the posts laid out on screen. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.shownPosts(): Set<Int> = onAllNodes(hasText("Запись ", substring = true)).fetchSemanticsNodes()
    .filter { it.isShown() }
    .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
    .mapNotNull { it.removePrefix("Запись ").toIntOrNull() }.toSet()

internal fun SemanticsNodeInteractionsProvider.screenshot(name: String) {
    val file = File("build/screens/$name.png").apply { parentFile.mkdirs() }
    ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
}

/** Clicks through the node's action: no hit-testing against a layout that may be a frame behind. */
internal fun SemanticsNodeInteraction.click() = performSemanticsAction(SemanticsActions.OnClick)

/** On screen: laid out inside the 860 px window (a list keeps some items composed just out of view). */
internal fun SemanticsNode.isShown() = boundsInRoot.let { it.bottom > 0f && it.top < 860f && it.height > 0f }

internal val MONTH_NAMES = listOf(
    "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
)
