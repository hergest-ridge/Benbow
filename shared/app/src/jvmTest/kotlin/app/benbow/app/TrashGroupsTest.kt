package app.benbow.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilExactlyOneExists
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals

/** The trash by days (owner): today, yesterday, this week, then months; everything or a day's group chosen at once. */
@OptIn(ExperimentalTestApi::class)
class TrashGroupsTest {
    private val services = feedTestServices()
    private val repo = services.repo

    @Test
    fun aDeletionFallsOnTodayYesterdayThisWeekOrItsMonth() {
        val today = LocalDate(2026, 10, 22) // a Thursday
        assertEquals(TrashGroup.Today, trashGroup(today, today))
        assertEquals(TrashGroup.Yesterday, trashGroup(LocalDate(2026, 10, 21), today))
        assertEquals(TrashGroup.ThisWeek, trashGroup(LocalDate(2026, 10, 19), today), "Monday is this week")
        assertEquals(TrashGroup.Month(2026, 10), trashGroup(LocalDate(2026, 10, 18), today), "Sunday before is not")
        assertEquals(TrashGroup.Month(2025, 12), trashGroup(LocalDate(2025, 12, 31), today))
        // On a Monday yesterday is still «Вчера», and nothing else falls on this week but today.
        assertEquals(TrashGroup.Yesterday, trashGroup(LocalDate(2026, 10, 18), LocalDate(2026, 10, 19)))
    }

    @Test
    fun runsFollowTheOrderAndThoseWithNoTimeYetStandApart() {
        val zone = TimeZone.UTC
        val at = { day: LocalDate -> day.atStartOfDayIn(zone).toEpochMilliseconds() + 3_600_000 }
        val now = at(LocalDate(2026, 10, 22))
        val items =
            listOf(now, at(LocalDate(2026, 10, 21)), at(LocalDate(2026, 10, 20)), at(LocalDate(2026, 9, 3)), at(LocalDate(2026, 9, 1)))
        val runs = trashRuns(items, now, zone) { it }
        assertEquals(listOf(TrashGroup.Today, TrashGroup.Yesterday, TrashGroup.ThisWeek, TrashGroup.Month(2026, 9)), runs.map { it.first })
        assertEquals(2, runs.last().second.size)
        assertEquals(listOf(null), trashRuns(listOf(1, 2), now, zone) { null }.map { it.first })
    }

    @Test
    fun everythingOrADaysGroupIsChosenAtOnce() = runDesktopComposeUiTest(width = 412, height = 860) {
        val ids = listOf("Первая", "Вторая").map { repo.createNote(services.today(), it) }
        ids.forEach { repo.moveToTrash(it) }
        setContent { BenbowApp(services) }
        onNodeWithText("Ещё").click()
        onNodeWithText("Корзина").click()
        waitUntilExactlyOneExists(hasText("Сегодня"), 5_000)
        screenshot("trash-05-groups")
        onNodeWithText("Выбрать").performClick()
        onNodeWithContentDescription("Выбрать все").performClick()
        waitUntilAtLeastOneExists(hasText("Выбрано: 2"), 5_000)
        screenshot("trash-06-all-chosen")
        onNodeWithContentDescription("Снять выбор").performClick()
        waitUntilAtLeastOneExists(hasText("Выбрано: 0"), 5_000)
        // A day's line chooses its whole group, and lets it go again.
        onNodeWithText("Сегодня").performClick()
        waitUntilAtLeastOneExists(hasText("Выбрано: 2"), 5_000)
        onNodeWithText("Сегодня").performClick()
        waitUntilAtLeastOneExists(hasText("Выбрано: 0"), 5_000)
    }
}
