package app.benbow.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilExactlyOneExists
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertTrue

/** The calendar's months by swipe, an empty month's nearest entries, the overview and reading from a day (7.14). */
@OptIn(ExperimentalTestApi::class)
class CalendarNavigationTest {
    private val services = feedTestServices()
    private val today = LocalDate.parse(services.today())

    @Test
    fun monthsSwipeAndAnEmptyOnePointsToTheNearestEntries() = runDesktopComposeUiTest(width = 412, height = 860) {
        val earlier = today.monthStart().minus(2, DateTimeUnit.MONTH)
        services.repo.createNote(earlier.plus(9, DateTimeUnit.DAY).toString(), "Давняя запись")
        services.repo.createNote(today.toString(), "Сегодняшняя")
        setContent { BenbowApp(services) }
        onNodeWithText("Календарь").click()
        waitUntilExactlyOneExists(title(today), 5_000)
        waitUntilExactlyOneExists(hasText("Сегодняшняя"), 5_000)

        // Back a month, by a swipe: nothing written there, so the 1st, and the nearest entries either way.
        swipeGrid(back = true)
        waitUntilExactlyOneExists(title(today.monthStart().minus(1, DateTimeUnit.MONTH)), 5_000)
        waitUntilAtLeastOneExists(hasText("Ближайшие записи"), 5_000)
        screenshot("90-calendar-empty-month")
        onNode(hasText(monthName(earlier), substring = true) and hasClickAction()).click()
        waitUntilExactlyOneExists(hasText("Давняя запись"), 5_000)
        waitUntilExactlyOneExists(title(earlier), 5_000)

        // A day's entry reads as in the feed, with its comments.
        onNodeWithText("Давняя запись").click()
        waitUntilExactlyOneExists(hasText("Комментарии"), 5_000)
        assertTrue(onAllNodes(hasSetTextAction() and hasText("Давняя запись", substring = true)).fetchSemanticsNodes().isEmpty())
        onNodeWithContentDescription("Назад").click()

        // Forward by a swipe, then «Сегодня».
        waitUntilExactlyOneExists(title(earlier), 5_000)
        swipeGrid(back = false)
        waitUntilExactlyOneExists(title(earlier.plus(1, DateTimeUnit.MONTH)), 5_000)
        onNodeWithContentDescription("Сегодня").click()
        waitUntilExactlyOneExists(title(today), 5_000)
        waitUntilExactlyOneExists(hasText("Сегодняшняя"), 5_000)
    }

    @Test
    fun theOverviewShowsTheYearsAndOpensAMonth() = runDesktopComposeUiTest(width = 412, height = 860) {
        val lastYear = today.year - 1
        services.repo.createNote("$lastYear-03-14", "Мартовская")
        services.repo.createNote("$lastYear-03-20", "Ещё в марте")
        services.repo.createNote(today.toString(), "Сегодняшняя")
        setContent { BenbowApp(services) }
        onNodeWithText("Календарь").click()
        waitUntilExactlyOneExists(title(today), 5_000)

        onNode(title(today)).click()
        waitUntilExactlyOneExists(hasContentDescription("Март $lastYear"), 5_000)
        screenshot("91-calendar-overview")
        // The journal's first day closes the list.
        onAllNodes(hasScrollToIndexAction()).filterToOne(hasAnyDescendant(hasContentDescription("Март $lastYear")))
            .performScrollToNode(hasText("Первая запись", substring = true))
        onNode(hasText("Первая запись — 14 марта $lastYear")).assertExists()

        // A month opens with its first day with entries.
        onNode(hasContentDescription("Март $lastYear")).click()
        waitUntilExactlyOneExists(title(LocalDate(lastYear, 3, 1)), 5_000)
        waitUntilExactlyOneExists(hasText("Мартовская"), 5_000)
    }

    /** The title bar's month (it opens the overview), as «🍂 Октябрь 2026». */
    private fun title(month: LocalDate): SemanticsMatcher = hasContentDescription("Обзор") and hasText(monthName(month), substring = true)

    private fun monthName(month: LocalDate) = "${MONTH_NAMES[month.month.ordinal]} ${month.year}"

    /** A finger across the whole month grid (the one thing on the screen that scrolls sideways). */
    private fun ComposeUiTest.swipeGrid(back: Boolean) {
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .performTouchInput { if (back) swipeRight() else swipeLeft() }
    }
}
