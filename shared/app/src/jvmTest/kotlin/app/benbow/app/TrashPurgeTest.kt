package app.benbow.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.client.ApiException
import app.benbow.core.api.PurgeDone
import app.benbow.core.api.PurgeFileInfo
import app.benbow.core.api.PurgePreview
import app.benbow.core.api.PurgeRequest
import app.benbow.core.api.StayingInfo
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Удалить навсегда» (ADR-041, 7.18 M5): choosing in the trash, the server's count, a held button, nothing by accident. */
@OptIn(ExperimentalTestApi::class)
class TrashPurgeTest {
    private val services = feedTestServices()
    private val repo = services.repo
    private val requests = mutableListOf<PurgeRequest>()
    private val commits = mutableListOf<String>()
    private val week = 7L * 24 * 3_600_000

    private fun preview(request: PurgeRequest, plan: String) = PurgePreview(
        planId = plan,
        entries = request.entries,
        links = request.attachments,
        files = listOf(PurgeFileInfo("a".repeat(64), 2_048, "video", "VID_1.mp4")),
        bytes = 2_048,
        staying = listOf(StayingInfo("b".repeat(64), 1_000, listOf("other"), live = true)),
        freesOn = services.now() + week,
    )

    private fun androidx.compose.ui.test.ComposeUiTest.openTrashAndChoose(title: String) {
        setContent { BenbowApp(services) }
        onNodeWithText("Ещё").click()
        onNodeWithText("Корзина").click()
        waitUntilAtLeastOneExists(hasText(title), 5_000)
        onNodeWithText("Выбрать").performClick()
        onNodeWithText(title).performClick()
        waitUntilAtLeastOneExists(hasText("Выбрано: 1"), 5_000)
        onNodeWithContentDescription("Удалить навсегда").performClick()
    }

    @Test
    fun aTapDoesNothingAndAHeldButtonDeletesWhatTheServerCounted() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = repo.createNote(services.today(), "Старая поездка")
        repo.moveToTrash(entry)
        services.purgePreviewer = { request ->
            requests += request
            preview(request, "plan-1")
        }
        services.purgeCommitter = { plan ->
            commits += plan
            PurgeDone("purge-1", 2_048, services.now() + week)
        }
        openTrashAndChoose("Старая поездка")
        waitUntilAtLeastOneExists(hasText("1 файл · 2${Char(0x00A0)}КБ"), 5_000)
        waitUntilAtLeastOneExists(hasText("останется: он нужен записям вне корзины", substring = true), 5_000)
        waitUntilAtLeastOneExists(hasText("Место освободится", substring = true), 5_000)
        assertEquals(listOf(PurgeRequest(entries = listOf(entry))), requests)
        screenshot("purge-01-sheet")

        // A tap, however firm, is not enough: down and up within a tenth of a second, the fill empties again.
        onNodeWithContentDescription("Удерживайте, чтобы удалить навсегда").performTouchInput {
            down(center)
            advanceEventTime(100)
            up()
        }
        mainClock.advanceTimeBy(2_000)
        assertEquals(emptyList(), commits)

        onNodeWithContentDescription("Удерживайте, чтобы удалить навсегда").performTouchInput { down(center) }
        mainClock.advanceTimeBy(1_700)
        // Done while the finger is still down: the sheet has gone with the button (no up to send).
        waitUntil(timeoutMillis = 5_000) { commits == listOf("plan-1") }
        waitUntilAtLeastOneExists(hasText("Удалено навсегда:", substring = true), 5_000)
        screenshot("purge-02-done")
    }

    @Test
    fun aPlanThatChangedShowsTheNewCountAndNoServerMeansNoDeleting() = runDesktopComposeUiTest(width = 412, height = 860) {
        val entry = repo.createNote(services.today(), "Две копии")
        repo.moveToTrash(entry)
        var previews = 0
        services.purgePreviewer = { request -> preview(request, "plan-${++previews}") }
        services.purgeCommitter = { plan ->
            if (plan == "plan-1") throw ApiException(409, "changed", "changed")
            commits += plan
            PurgeDone("purge", 2_048, services.now() + week)
        }
        openTrashAndChoose("Две копии")
        waitUntilAtLeastOneExists(hasText("1 файл", substring = true), 5_000)
        onNodeWithContentDescription("Удерживайте, чтобы удалить навсегда").performTouchInput { down(center) }
        mainClock.advanceTimeBy(1_700)
        onNodeWithContentDescription("Удерживайте, чтобы удалить навсегда").performTouchInput { up() }
        waitUntilAtLeastOneExists(hasText("что-то изменилось", substring = true), 5_000)
        assertTrue(previews >= 2, "counted again")
        assertEquals(emptyList(), commits)

        services.purgePreviewer = { throw IOException("no route") }
        onNodeWithText("Отмена").performClick()
        onNodeWithContentDescription("Удалить навсегда").performClick()
        waitUntilAtLeastOneExists(hasText("связи с ним сейчас нет", substring = true), 5_000)
        screenshot("purge-03-offline")
    }
}
