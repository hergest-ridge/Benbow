package app.benbow.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.client.MediaFile
import app.benbow.core.api.PurgeDone
import app.benbow.core.api.PurgeFileInfo
import app.benbow.core.api.PurgePreview
import app.benbow.core.api.PurgeRequest
import app.benbow.core.api.StorageReport
import app.benbow.core.api.UnreferencedFile
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The trash's helpers and «Без записи» (ADR-041, 7.18 M6): choosing the heaviest, by kind and age, nothing final yet. */
@OptIn(ExperimentalTestApi::class)
class TrashHelpersTest {
    private val services = feedTestServices()
    private val repo = services.repo

    private fun bytes(size: Int, seed: Int, name: String, mime: String): MediaFile =
        runBlocking { services.media.import(Random(seed).nextBytes(size).inputStream(), mime, name, null) }

    @Test
    fun theHeaviestAreChosenUntilTheyHoldTheTarget() {
        val picks =
            listOf(
                TrashPick("a", 10, null, false),
                TrashPick("b", 50, null, false),
                TrashPick("c", 30, null, false),
                TrashPick("d", 0, null, false),
            )
        assertEquals(setOf("b"), heaviestUpTo(picks, 40))
        assertEquals(setOf("b", "c"), heaviestUpTo(picks, 60))
        assertEquals(setOf("b", "c", "a"), heaviestUpTo(picks, 1_000), "never an empty one")
        assertEquals(emptySet(), heaviestUpTo(picks, 0))
    }

    @Test
    fun helpersChooseByKindAndWeightAndTheFreeDialogTakesTheHeaviest() = runDesktopComposeUiTest(width = 412, height = 860) {
        val film = repo.createNote(services.today(), "Фильм с моря")
        repo.attach(film, bytes(40_000, 1, "VID_1.mp4", "video/mp4"), "video")
        val photos = repo.createNote(services.today(), "Фото")
        repo.attach(photos, bytes(9_000, 2, "IMG_1.jpg", "image/jpeg"), "image")
        val words = repo.createNote(services.today(), "Просто слова")
        listOf(film, photos, words).forEach { repo.moveToTrash(it) }
        setContent { BenbowApp(services) }
        onNodeWithText("Ещё").click()
        onNodeWithText("Корзина").click()
        waitUntilAtLeastOneExists(hasText("Фильм с моря"), 5_000)
        onNode(hasText("Больше 100 МБ") and isNotEnabled()).assertExists("nothing that big here")

        onNodeWithText("Видео").performClick()
        waitUntilAtLeastOneExists(hasText("Выбрано: 1"), 5_000)
        waitUntilAtLeastOneExists(hasText("Освободит до 39${Char(0x00A0)}КБ", substring = true), 5_000)
        screenshot("trash-04-helper-video")

        onNodeWithText("Освободить…").performClick()
        waitUntilAtLeastOneExists(hasText("Будет выбрано: 2", substring = true), 5_000) // 5 GB is more than all of it
        screenshot("trash-05-free-dialog")
        onNode(hasText("Выбрать") and isEnabled()).performClick()
        waitUntilAtLeastOneExists(hasText("Выбрано: 2"), 5_000) // the words hold no file: never chosen for space
    }

    @Test
    fun filesWithoutAnEntryGoThroughTheSameSheet() = runDesktopComposeUiTest(width = 412, height = 860) {
        val orphan = UnreferencedFile("c".repeat(64), 7_340_032, "image/jpeg", "2025/08/2025-08-01_120000_cccccccc.jpg", 1_754_000_000_000)
        var asked: PurgeRequest? = null
        services.storageReader = { StorageReport(files = 1, bytes = orphan.size, states = mapOf("unreferenced" to orphan.size)) }
        services.unreferencedReader = { listOf(orphan) }
        services.purgePreviewer = { request ->
            asked = request
            PurgePreview(
                "p",
                emptyList(),
                emptyList(),
                listOf(PurgeFileInfo(orphan.sha256, orphan.size)),
                orphan.size,
                freesOn = services.now(),
            )
        }
        services.purgeCommitter = { PurgeDone("x", orphan.size, services.now()) }
        setContent { BenbowApp(services) }
        onNodeWithText("Ещё").click()
        onNodeWithText("Медиатека").click()
        onNodeWithContentDescription("Место").performClick()
        waitUntilAtLeastOneExists(hasText("Без записи"), 5_000)
        waitUntilAtLeastOneExists(hasText("2025-08-01_120000_cccccccc.jpg"), 5_000)
        screenshot("storage-06-unreferenced")
        onNodeWithText("Удалить навсегда").performClick()
        waitUntilAtLeastOneExists(hasText("1 файл", substring = true), 5_000)
        assertEquals(PurgeRequest(unreferenced = listOf(orphan.sha256)), asked)
    }
}
