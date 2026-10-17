package app.benbow.core.audio

/** ID3v2.2-2.4 (a file's start, and chunks inside WAV and AIFF) and ID3v1 (a file's last 128 bytes), after id3.org. */
internal object Id3 {
    /** The whole tag's length from "ID3" (header, frames, padding, footer), or null when [head] has none at [at]. */
    fun length(head: ByteArray, at: Int = 0): Int? {
        if (head.size < at + 10 || !head.has("ID3", at)) return null
        val major = head.u8(at + 3)
        if (major !in 2..4) return null
        val footer = if (major == 4 && head.u8(at + 5) and 0x10 != 0) 10 else 0
        return 10 + head.syncsafe(at + 6) + footer
    }

    fun parse(tag: ByteArray): Found? {
        if (tag.size < 10 || !tag.has("ID3")) return null
        val major = tag.u8(3)
        if (major !in 2..4) return null
        val flags = tag.u8(5)
        var body = tag.copyOfRange(10, minOf(tag.size, 10 + tag.syncsafe(6)))
        if (major < 4 && flags and 0x80 != 0) body = resync(body, 0, body.size)
        var i = 0
        if (flags and 0x40 != 0 && major >= 3 && body.size >= 4) i = if (major == 3) 4 + body.u32be(0).toInt() else body.syncsafe(0)
        val found = Found()
        val head = if (major == 2) 6 else 10
        while (i in 0..body.size - head) {
            if (body.u8(i) == 0) break // padding
            if (!isId(body, i, if (major == 2) 3 else 4)) break
            val size = when (major) {
                2 -> body.u24be(i + 3)
                3 -> body.u32be(i + 4).toInt()
                else -> size24(body, i)
            }
            val start = i + head
            if (size <= 0 || start + size > body.size) break
            frame(found, major, body.name(i, if (major == 2) 3 else 4), body, start, start + size, if (major == 2) 0 else body.u8(i + 9))
            i = start + size
        }
        return found
    }

    /** ID3v1: 30 bytes each for title, artist and album. */
    fun v1(last: ByteArray): TrackTags? {
        if (last.size != 128 || !last.has("TAG")) return null
        fun field(from: Int) = TagText.legacy(last, from, last.zero(from, from + 30))
        return TrackTags(field(3), field(33), field(63)).takeUnless { it.isEmpty }
    }

    /** What the frames said; the front cover wins over other pictures, else the first. */
    class Found {
        var title: String? = null
        var artist: String? = null
        var band: String? = null
        var album: String? = null
        var cover: ByteArray? = null
        private var front = false

        val tags: TrackTags get() = TrackTags(title, artist ?: band, album)

        fun picture(type: Int, bytes: ByteArray) {
            if (bytes.isEmpty() || front) return
            if (cover == null || type == FRONT_COVER) cover = bytes
            front = type == FRONT_COVER
        }
    }

    private fun frame(found: Found, major: Int, id: String, body: ByteArray, from: Int, to: Int, format: Int) {
        var data = body
        var s = from
        var e = to
        when (major) {
            3 -> {
                if (format and 0xC0 != 0) return // compressed or encrypted
                if (format and 0x20 != 0) s += 1
            }

            4 -> {
                if (format and 0x0C != 0) return
                if (format and 0x40 != 0) s += 1
                if (format and 0x01 != 0) s += 4
                if (format and 0x02 != 0) {
                    data = resync(body, s, e)
                    s = 0
                    e = data.size
                }
            }
        }
        if (s >= e) return
        when (id) {
            "TIT2", "TT2" -> found.title = found.title ?: values(data, s, e).firstOrNull()
            "TPE1", "TP1" -> found.artist = found.artist ?: values(data, s, e).distinct().joinToString(", ").ifEmpty { null }
            "TPE2", "TP2" -> found.band = found.band ?: values(data, s, e).firstOrNull()
            "TALB", "TAL" -> found.album = found.album ?: values(data, s, e).firstOrNull()
            "APIC" -> picture(found, data, s, e, short = false)
            "PIC" -> picture(found, data, s, e, short = true)
        }
    }

    /** A text frame: its encoding byte, then one or more values ended by zeros (ID3v2.4 allows several). */
    private fun values(data: ByteArray, from: Int, to: Int): List<String> {
        val encoding = data.u8(from)
        val wide = encoding == 1 || encoding == 2
        val out = mutableListOf<String>()
        var s = from + 1
        while (s < to) {
            val z = data.zero(s, to, wide)
            decode(encoding, data, s, z)?.let(out::add)
            s = z + if (wide) 2 else 1
        }
        return out
    }

    private fun decode(encoding: Int, data: ByteArray, from: Int, to: Int): String? = when (encoding) {
        1 -> TagText.utf16(data, from, to)
        2 -> TagText.utf16(data, from, to, bigEndian = true)
        3 -> TagText.utf8(data, from, to)
        else -> TagText.legacy(data, from, to)
    }

    /** APIC: encoding, MIME type, picture type, description, the image. ID3v2.2's PIC has a three-letter format instead. */
    private fun picture(found: Found, data: ByteArray, from: Int, to: Int, short: Boolean) {
        val encoding = data.u8(from)
        var i = if (short) from + 4 else data.zero(from + 1, to) + 1
        if (i >= to) return
        val type = data.u8(i++)
        val wide = encoding == 1 || encoding == 2
        i = data.zero(i, to, wide) + if (wide) 2 else 1
        if (i < to) found.picture(type, data.copyOfRange(i, to))
    }

    /**
     * ID3v2.4 frame sizes are syncsafe, but iTunes once wrote them plain: take the reading after which another frame
     * (or the padding, or the end) follows.
     */
    private fun size24(body: ByteArray, i: Int): Int {
        val plain = body.u32be(i + 4).toInt()
        if ((body.u8(i + 4) or body.u8(i + 5) or body.u8(i + 6) or body.u8(i + 7)) and 0x80 != 0) return plain
        val safe = body.syncsafe(i + 4)
        if (safe == plain) return safe

        fun fits(size: Int): Boolean {
            val next = i + 10 + size
            return next == body.size || (next + 4 <= body.size && (body.u8(next) == 0 || isId(body, next, 4)))
        }
        return if (!fits(safe) && fits(plain)) plain else safe
    }

    private fun isId(bytes: ByteArray, at: Int, length: Int): Boolean =
        (0 until length).all { bytes.u8(at + it).let { c -> c in 'A'.code..'Z'.code || c in '0'.code..'9'.code } }

    /** Undoes unsynchronisation: a zero put after every 0xFF so no frame sync shows up in the tag. */
    private fun resync(bytes: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArray(to - from)
        var n = 0
        var i = from
        while (i < to) {
            out[n++] = bytes[i]
            if (bytes.u8(i) == 0xFF && i + 1 < to && bytes[i + 1].toInt() == 0) i++
            i++
        }
        return out.copyOf(n)
    }

    private const val FRONT_COVER = 3
}
