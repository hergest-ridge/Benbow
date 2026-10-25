package app.benbow.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A picture's size from its first bytes (a link that is a picture keeps it, ADR-038). */
class PictureSizeTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun pngGifJpegAndWebpTellTheirSize() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 2, 0x80, 0, 0, 1, 0xE0)
        assertEquals(640 to 480, PictureSize.of(png))
        val gif = "GIF89a".encodeToByteArray() + bytes(0xE0, 0x01, 0x0E, 0x01, 0, 0, 0)
        assertEquals(480 to 270, PictureSize.of(gif))
        // SOI, an APP0 segment to step over, then SOF0: precision, height 300, width 400.
        val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 4, 0, 0, 0xFF, 0xC0, 0, 17, 8, 0x01, 0x2C, 0x01, 0x90, 3, 0, 0, 0, 0, 0)
        assertEquals(400 to 300, PictureSize.of(jpeg))
        val vp8x = "RIFF".encodeToByteArray() + bytes(0, 0, 0, 0) + "WEBPVP8X".encodeToByteArray() +
            bytes(10, 0, 0, 0, 0x10, 0, 0, 0, 0x1F, 0x03, 0, 0x57, 0x02, 0, 0, 0)
        assertEquals(800 to 600, PictureSize.of(vp8x))
        val vp8l = "RIFF".encodeToByteArray() + bytes(0, 0, 0, 0) + "WEBPVP8L".encodeToByteArray() +
            bytes(0, 0, 0, 0, 0x2F, 0x63, 0x40, 0x0E, 0, 0, 0, 0, 0, 0)
        assertEquals(100 to 58, PictureSize.of(vp8l))
    }

    @Test
    fun anythingElseOrACutFileHasNoSize() {
        assertNull(PictureSize.of("<html>".encodeToByteArray()))
        assertNull(PictureSize.of(bytes(0x89, 0x50, 0x4E, 0x47)))
        assertNull(PictureSize.of(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 4)))
        assertNull(PictureSize.of(ByteArray(0)))
    }
}
