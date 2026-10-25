package app.benbow.core.media

/**
 * A picture's width and height read from its first bytes, without decoding it: PNG, GIF, JPEG and WebP, the same on
 * every device and the server. A link that is a picture keeps them, so its card has its height before the picture
 * loads (ADR-038). Null for anything else or a cut file.
 */
object PictureSize {
    fun of(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        when {
            bytes.startsWith(PNG) -> (int32(bytes, 16) to int32(bytes, 20))
            bytes.startsWith(GIF87) || bytes.startsWith(GIF89) -> (int16le(bytes, 6) to int16le(bytes, 8))
            bytes.size > 3 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 -> jpeg(bytes)
            bytes.startsWith(RIFF) && bytes.size >= 30 && bytes.copyOfRange(8, 12).contentEquals(WEBP) -> webp(bytes)
            else -> null
        }
    }.getOrNull()?.takeIf { (w, h) -> w > 0 && h > 0 }

    /** The first start-of-frame marker's size (EXIF turns are left to the decoder that shows it). */
    private fun jpeg(b: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < b.size) {
            if (u8(b, i) != 0xFF) return null
            val marker = u8(b, i + 1)
            if (marker == 0xFF) {
                i++
                continue
            }
            val length = int16(b, i + 2)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) return int16(b, i + 7) to int16(b, i + 5)
            i += 2 + length
        }
        return null
    }

    private fun webp(b: ByteArray): Pair<Int, Int>? = when (b.copyOfRange(12, 16).decodeToString()) {
        "VP8 " -> (int16le(b, 26) and 0x3FFF) to (int16le(b, 28) and 0x3FFF)

        "VP8L" -> {
            val bits = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
            ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
        }

        "VP8X" -> (int24le(b, 24) + 1) to (int24le(b, 27) + 1)

        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun int16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    private fun int16le(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    private fun int24le(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)

    private fun int32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val GIF87 = "GIF87a".encodeToByteArray()
    private val GIF89 = "GIF89a".encodeToByteArray()
    private val RIFF = "RIFF".encodeToByteArray()
    private val WEBP = "WEBP".encodeToByteArray()
}
