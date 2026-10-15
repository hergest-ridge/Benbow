package app.benbow.client

import app.benbow.client.calendar.IcsImport
import app.benbow.core.calendar.EventField
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** ZIP in the browser (ADR-029): the export written from blobs, calendar archives read back, zones by name. */
class ZipTest {
    @Test
    fun theExportReadsBack() = runTest {
        val video = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val zip = BlobZipWriter(LocalDateTime(2026, 10, 16, 12, 30))
        zip.text("README.md", "# Benbow export")
        zip.file("media/2026/10/закат.mp4", LocalFile("sha", blobOf(video.toUint8Array(), "video/mp4"), video.size.toLong()))
        zip.text("entries/2026/10/2026-10-16_a.md", "Тёплый вечер")
        val files = readZip(readBlob(zip.finish()), Long.MAX_VALUE).toMap()

        assertEquals(listOf("README.md", "media/2026/10/закат.mp4", "entries/2026/10/2026-10-16_a.md"), files.keys.toList())
        assertContentEquals(video, files.getValue("media/2026/10/закат.mp4"), "a blob goes in as it is")
        assertEquals("Тёплый вечер", files.getValue("entries/2026/10/2026-10-16_a.md").decodeToString())
    }

    /** As Google packs a calendar export: deflated, a Cyrillic file name. */
    private val google = Base64.decode(
        "UEsDBBQAAAgIALoNUF3eCQ4utgAAAPQAAAAuAAAA0KDQsNCx0L7RgtCwX2FiY0Bncm91cC5jYWxlbmRhci5nb29nbGUuY29tLmljc3Nydff0swpzdvRx9XNxDOLl" +
            "CnMNCvb097My0jPg5YrQDQ/SBcr5Ofq6Wl1YcGHDhY0X9l1surCBl8sJotE1zNUvhJcr1NPFKq/SkJcrONTX1zEo0urCwgv7Lmy/sAlI7lW42KhwYe7Fnot9uhdm" +
            "AvU3XNgFFN7Dy+USEhziGBRiHRLl6WLrmJtalJmcqO+XWh4fmV+UbWVkYGRmaGBoGmJoAAIg5UA3ElRsCFEMVAp3HZiN8CIAUEsDBBQAAAAIALoNUF0D7kzGFgAA" +
            "ACwBAAAKAAAAcmVhZG1lLnR4dMvLL1FIVEhOzEnNS0ksUsgb5eLmAgBQSwECFAAUAAAICAC6DVBd3gkOLrYAAAD0AAAALgAAAAAAAAAAAAAAgAEAAAAA0KDQsNCx" +
            "0L7RgtCwX2FiY0Bncm91cC5jYWxlbmRhci5nb29nbGUuY29tLmljc1BLAQIUABQAAAAIALoNUF0D7kzGFgAAACwBAAAKAAAAAAAAAAAAAACAAQIBAAByZWFkbWUu" +
            "dHh0UEsFBgAAAAACAAIAlAAAAEABAAAAAA==",
    )

    @Test
    fun aDeflatedCalendarArchiveImportsWithItsZones() = runTest {
        installTimeZones()
        val calendar = IcsImport.read("calendar-export.zip", google, TimeZone.of("Europe/Warsaw")).single()
        assertEquals("Работа", calendar.name)
        val call = calendar.events.single().fields
        assertEquals("2026-10-15T16:00", call[EventField.START], "10:00 in New York is 16:00 in Warsaw")
        assertEquals("2026-10-15T17:00", call[EventField.END])
        assertEquals(0, calendar.unknownZones)
    }

    @Test
    fun aZipBombIsRefused() = runTest {
        assertFailsWith<IllegalArgumentException> { readZip(google, maxTotal = 100) }
        assertFailsWith<IllegalArgumentException> { readZip("not a zip".encodeToByteArray(), Long.MAX_VALUE) }
    }
}
