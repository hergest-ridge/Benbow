package app.benbow.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.client.Veil
import app.benbow.client.addTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «Перейти», the filter and «Показать в ленте» on screen (ADR-036 N4-N6). */
@OptIn(ExperimentalTestApi::class)
class FeedToolsTest {
    private val services = feedTestServices()

    @Test
    fun jumpGoesToAMonthAtRandomAndBackToToday() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.seedPosts(POSTS)
        val today = LocalDate.parse(services.today())
        val oldest = today.minus((POSTS - 1) / 2, DateTimeUnit.DAY)
        val oldestMonth = oldest.toString().substring(5, 7).toInt() - 1
        // The posts of the oldest month are the lowest numbers, however many days of it the feed reaches (a month
        // cut short in the past, or a whole January when today is late October).
        val oldestMonthPosts = (0 until POSTS).count { i ->
            today.minus(i / 2, DateTimeUnit.DAY).let { it.year == oldest.year && it.month == oldest.month }
        }
        // A month's title has its year only when it isn't this year's.
        val oldestTitle = MONTH_NAMES[oldestMonth] + if (oldest.year != today.year) " ${oldest.year}" else ""
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntil(timeoutMillis = 5_000) { shownPosts().contains(POSTS - 1) }

        // The oldest month, from the years and months: the feed lands on its posts.
        onNodeWithContentDescription("Перейти к дате").click()
        waitUntilAtLeastOneExists(hasText("Наугад"), 5_000)
        onNode(hasText("Год назад") and hasClickAction()).assertIsNotEnabled() // the journal is younger than a year
        screenshot("84-feed-jump")
        onNode(hasText(MONTH_NAMES[oldestMonth].take(3)) and hasClickAction() and isEnabled()).click()
        waitUntil(timeoutMillis = 5_000) { shownPosts().any { it < oldestMonthPosts } }

        // A month's title opens it too; «Сегодня» goes back.
        onAllNodes(hasText(oldestTitle, substring = true) and hasClickAction()).onFirst().click()
        waitUntilAtLeastOneExists(hasText("Сегодня") and hasClickAction(), 5_000)
        onNode(hasText("Сегодня") and hasClickAction()).click()
        waitUntil(timeoutMillis = 5_000) { shownPosts().contains(POSTS - 1) }

        // At random: a day older than a month.
        onNodeWithContentDescription("Перейти к дате").click()
        waitUntilAtLeastOneExists(hasText("Наугад"), 5_000)
        onNode(hasText("Наугад") and hasClickAction()).click()
        waitUntil(timeoutMillis = 5_000) { shownPosts().let { it.isNotEmpty() && it.max() < POSTS - 60 } }
    }

    @Test
    fun aFilterShowsASubjectWithItsQuietPostsAndOutlastsATabSwitch() = runDesktopComposeUiTest(width = 412, height = 860) {
        val repo = services.repo
        val today = LocalDate.parse(services.today())
        // 120 posts over two months, every tenth a trip; one trip is quiet. Two posts more than two years back.
        (0 until 120).forEach { i ->
            val number = 119 - i
            val id = repo.createNote(today.minus(i / 2, DateTimeUnit.DAY).toString(), "Запись $number")
            if (number % 10 == 0) repo.addTag(id, "поездки")
            if (number == 110) repo.setVeil(id, Veil.Quiet)
        }
        val longAgo = today.minus(800, DateTimeUnit.DAY)
        repeat(2) { repo.createNote(longAgo.toString(), "Давно $it") }
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntil(timeoutMillis = 5_000) { shownPosts().contains(119) }
        assertFalse(110 in shownPosts(), "the quiet trip stays out of the feed")

        onNodeWithContentDescription("Фильтр").click()
        waitUntilAtLeastOneExists(hasText("Теги"), 5_000)
        onNode(hasText("поездки") and isFilterChip).click()
        onNodeWithText("Готово").click()
        waitUntil(timeoutMillis = 5_000) { shownPosts().let { it.isNotEmpty() && it.all { n -> n % 10 == 0 } } }
        assertTrue(onAllNodes(hasText("Открыть")).fetchSemanticsNodes().isNotEmpty(), "the quiet trip comes, under a curtain")
        screenshot("85-feed-filter")

        // It outlasts a switch of tabs.
        onNodeWithText("Главная").click()
        onNodeWithText("Лента").click()
        waitUntil(timeoutMillis = 5_000) { shownPosts().let { it.isNotEmpty() && it.all { n -> n % 10 == 0 } } }

        // Its chip under the title lets it go (the topmost «поездки»: the posts below carry the tag too).
        val chips = onAllNodes(hasText("поездки") and hasClickAction())
        val top = chips.fetchSemanticsNodes().withIndex().minBy { it.value.boundsInRoot.top }.index
        chips[top].click()
        waitUntil(timeoutMillis = 5_000) { shownPosts().contains(119) }

        // Trips more than two years back: none; «Сбросить» brings the whole feed.
        onNodeWithContentDescription("Фильтр").click()
        waitUntilAtLeastOneExists(hasText("Теги"), 5_000)
        onNode(hasText("поездки") and isFilterChip).click()
        onNode(hasText(longAgo.year.toString()) and isFilterChip).click()
        onNodeWithText("Готово").click()
        waitUntilAtLeastOneExists(hasText("По этому фильтру в ленте ничего нет"), 5_000)
        onAllNodes(hasText("Сбросить") and hasClickAction()).onFirst().click()
        waitUntil(timeoutMillis = 5_000) { shownPosts().contains(119) }
        assertEquals(FeedFilter(), services.feedFilter.value)
    }

    @Test
    fun showInFeedFindsThePostPastAFilterButNotPastBerezhno() = runDesktopComposeUiTest(width = 412, height = 860) {
        val numbers = services.seedPosts(POSTS)
        val target = numbers.entries.single { it.value == 100 }.key
        val quiet = services.repo.createNote(services.today(), "Тихая запись")
        services.repo.setVeil(quiet, Veil.Quiet)
        setContent { BenbowApp(services) }
        onNodeWithText("Лента").click()
        waitUntil(timeoutMillis = 5_000) { shownPosts().contains(POSTS - 1) }

        // Open it from deep in the feed.
        val rows = feedRows(runBlocking { services.repo.feedIndex().first() }.filter { it.veil != Veil.Quiet })
        onNode(hasScrollToIndexAction()).performScrollToIndex(rows.indexOfFirst { it.key == target })
        waitUntil(timeoutMillis = 5_000) { 100 in shownPosts() }
        onAllNodes(hasText("Запись 100")).onFirst().click()
        waitUntilAtLeastOneExists(hasText("Пока без комментариев", substring = true), 5_000)

        // A filter that would hide it, set meanwhile, is let go; the feed opens at it.
        services.feedFilter.value = FeedFilter(years = setOf("1999"))
        onNodeWithContentDescription("Меню").click()
        onNodeWithText("Показать в ленте").click()
        waitUntil(timeoutMillis = 5_000) { 100 in shownPosts() }
        assertEquals(FeedFilter(), services.feedFilter.value)
        assertTrue(onAllNodes(hasText("Моя лента")).fetchSemanticsNodes().isNotEmpty())
        screenshot("86-feed-shown")

        // One Бережно keeps out stays out, and the app says so.
        services.showInFeed.value = quiet
        waitUntilAtLeastOneExists(hasText("её прячет Бережно", substring = true), 5_000)
    }

    private companion object {
        const val POSTS = 600

        /** The filter sheet's chips (tags on the posts below are clickable chips too). */
        val isFilterChip = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
    }
}
