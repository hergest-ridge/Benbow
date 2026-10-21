package app.benbow.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.client.MediaFile
import app.benbow.core.api.DiskSpace
import app.benbow.core.api.StorageMonth
import app.benbow.core.api.StorageReport
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Место» (ADR-041, 7.18 M4): the server's count in bars, months and words, the heaviest as a map, this device. */
@OptIn(ExperimentalTestApi::class)
class StorageScreenTest {
    private val services = feedTestServices()
    private val repo = services.repo
    private val gb = 1024L * 1024 * 1024

    private val report = StorageReport(
        files = 1_234,
        bytes = 48 * gb + gb / 3,
        kinds = mapOf(
            "video" to 31 * gb,
            "image" to 12 * gb,
            "sound" to 3 * gb,
            "drawing" to gb / 10,
            "file" to 2 * gb,
            "preview" to gb / 5,
        ),
        states = mapOf("live" to 41 * gb, "trash" to 5 * gb, "removed" to 2 * gb, "unreferenced" to gb / 3),
        months = listOf(
            StorageMonth("2025-05", mapOf("video" to 6 * gb, "image" to gb)),
            StorageMonth("2025-12", mapOf("image" to 2 * gb)),
            StorageMonth("2026-10", mapOf("video" to 3 * gb, "sound" to gb / 2)),
        ),
        disk = DiskSpace(total = 931 * gb, free = 312 * gb),
        mirror = DiskSpace(total = 1_863 * gb, free = 1_200 * gb),
        database = 48 * 1024 * 1024,
        snapshots = 900 * 1024 * 1024,
        counted = 1_792_000_000_000,
    )

    /** A picture of one colour and [noise] specks, which make it heavier. */
    private fun picture(n: Int, noise: Int): MediaFile {
        val image = BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.getHSBColor(n / 7f, 0.5f, 0.8f)
            fillRect(0, 0, 160, 120)
            dispose()
        }
        val random = Random(n)
        repeat(noise) { image.setRGB(random.nextInt(160), random.nextInt(120), random.nextInt()) }
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return runBlocking { services.media.import(png.inputStream(), "image/png", "p$n.png", null) }
    }

    @Test
    fun theServersCountShowsInBarsMonthsAndWordsTheHeaviestAsAMap() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.storageReader = { report }
        val entry = repo.createNote(services.today(), "Поездка")
        listOf(6_000, 3_000, 1_500, 600, 200).forEachIndexed { i, noise -> repo.attach(entry, picture(i + 1, noise), "image", 160, 120) }
        setContent { BenbowApp(services) }
        onNodeWithText("Ещё").click()
        onNodeWithText("Медиатека").click()
        onNodeWithContentDescription("Место").performClick()
        waitUntilAtLeastOneExists(hasText("48${Char(0x00A0)}ГБ"), 5_000) // whole above ten
        waitUntilAtLeastOneExists(hasText("1234 файла на сервере", substring = true), 5_000)
        waitUntilAtLeastOneExists(hasText("Свободно"), 5_000)
        screenshot("storage-01-hero")
        onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Где оно"))
        waitUntilAtLeastOneExists(hasText("Сняты с записей"), 5_000)
        screenshot("storage-02-kinds-states")
        onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("По месяцам записей"))
        // The newest month is picked to begin with; a tap picks another.
        waitUntilAtLeastOneExists(hasText("Октябрь 2026:", substring = true), 5_000)
        screenshot("storage-03-months")
        onNodeWithContentDescription("2025-12").click()
        waitUntilAtLeastOneExists(hasText("Декабрь 2025:", substring = true), 5_000)
        onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Самое тяжёлое"))
        waitUntilAtLeastOneExists(hasContentDescription("p1.png"), 5_000)
        screenshot("storage-04-heaviest")
        onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Резервные копии"))
        // Only just captured here: nothing may go yet.
        waitUntilAtLeastOneExists(hasText("Освобождать нечего", substring = true), 5_000)
        waitUntilAtLeastOneExists(
            hasText("свободно 1,2${Char(0x00A0)}ТБ", substring = true),
            5_000,
        )
        screenshot("storage-05-device-backups")
    }

    @Test
    fun aServerAwaySaysSoAndKeepsTheDevicesPart() = runDesktopComposeUiTest(width = 412, height = 860) {
        services.storageReader = { throw IOException("no route") }
        setContent { BenbowApp(services) }
        onNodeWithText("Ещё").click()
        onNodeWithText("Медиатека").click()
        onNodeWithContentDescription("Место").performClick()
        waitUntilAtLeastOneExists(hasText("Нет связи с сервером", substring = true), 5_000)
        waitUntilAtLeastOneExists(hasText("На этом устройстве"), 5_000)
    }

    @Test
    fun aTreemapFillsItsBoxWithTilesAsLargeAsTheirWeights() {
        val weights = listOf(60.0, 25.0, 9.0, 4.0, 1.5, 0.5)
        val tiles = squarify(weights, 400f, 300f)
        assertEquals(weights.indices.toList(), tiles.map { it.index })
        tiles.forEach { tile ->
            assertTrue(tile.x >= -0.01f && tile.y >= -0.01f && tile.x + tile.w <= 400.01f && tile.y + tile.h <= 300.01f, "$tile")
            assertEquals(weights[tile.index] / 100 * 400 * 300, (tile.w * tile.h).toDouble(), 1.0)
        }
        for (a in tiles) {
            for (b in tiles) {
                if (a.index >= b.index) continue
                val apart = a.x + a.w <= b.x + 0.01f || b.x + b.w <= a.x + 0.01f || a.y + a.h <= b.y + 0.01f || b.y + b.h <= a.y + 0.01f
                assertTrue(apart, "$a overlaps $b")
            }
        }
        assertEquals(emptyList(), squarify(emptyList(), 400f, 300f))
    }

    @Test
    fun monthsRunEvenlyEmptyOnesToo() {
        val months = continuous(listOf(StorageMonth("2025-11", mapOf("video" to 1)), StorageMonth("2026-02", mapOf("image" to 2))))
        assertEquals(listOf("2025-11", "2025-12", "2026-01", "2026-02"), months.map { it.month })
        assertEquals(emptyMap(), months[1].kinds)
    }
}
