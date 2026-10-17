package app.benbow.app

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Made-up sound files for the app's tests (ADR-035): silent MP3 frames with an ID3 tag and a cover. */
internal object TestSounds {
    /** Silence as MPEG-1 Layer III frames: a Xing header counting 1000 frames (26 s), then two plain ones. */
    val MPEG_FRAMES: ByteArray = run {
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0)
        val xing = (header + ByteArray(32) + "Xing".toByteArray() + int(1) + int(1000)).copyOf(417)
        xing + (header + ByteArray(413)) + (header + ByteArray(413))
    }

    /** ID3v2.3 with title, artist and album in UTF-16 and a front cover, then the frames. */
    fun mp3(title: String, artist: String, cover: ByteArray? = null, album: String? = null): ByteArray {
        fun text(value: String) = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE)
        fun frame(id: String, data: ByteArray) = id.toByteArray() + int(data.size) + byteArrayOf(0, 0) + data
        var body = frame("TIT2", text(title)) + frame("TPE1", text(artist))
        if (album != null) body += frame("TALB", text(album))
        if (cover != null) body += frame("APIC", byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 3, 0) + cover)
        body += ByteArray(64)
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncsafe(body.size) + body + MPEG_FRAMES
    }

    /** A cover: two bands of color, like an album sleeve's. */
    fun jpeg(width: Int, height: Int, top: Color = Color(0x8E, 0x44, 0xAD), bottom: Color = Color(0x2C, 0x3E, 0x50)): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = top
            fillRect(0, 0, width, height * 3 / 5)
            color = bottom
            fillRect(0, height * 3 / 5, width, height * 2 / 5)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun int(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun syncsafe(v: Int) = byteArrayOf(
        (v shr 21 and 0x7F).toByte(),
        (v shr 14 and 0x7F).toByte(),
        (v shr 7 and 0x7F).toByte(),
        (
            v and
                0x7F
            ).toByte(),
    )
}
