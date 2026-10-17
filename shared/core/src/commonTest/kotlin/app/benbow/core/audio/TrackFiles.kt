package app.benbow.core.audio

// Small made-up sound files for the reader's tests: headers and tags as the formats write them, silence for audio.

internal fun ascii(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

internal fun utf8(text: String): ByteArray = text.encodeToByteArray()

/** Windows-1251 for Russian letters, ASCII for the rest. */
internal fun cp1251(text: String): ByteArray = ByteArray(text.length) {
    val c = text[it]
    when (c) {
        in 'А'..'я' -> (0xC0 + (c.code - 'А'.code)).toByte()
        'Ё' -> 0xA8.toByte()
        'ё' -> 0xB8.toByte()
        else -> c.code.toByte()
    }
}

internal fun latin1(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

internal fun utf16le(text: String, bom: Boolean = true): ByteArray {
    val out = ArrayList<Byte>()
    if (bom) {
        out += 0xFF.toByte()
        out += 0xFE.toByte()
    }
    text.forEach {
        out += (it.code and 0xFF).toByte()
        out += (it.code shr 8).toByte()
    }
    return out.toByteArray()
}

internal fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

internal fun be24(v: Int) = byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

internal fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

internal fun be32(v: Int) = be32(v.toLong())

internal fun be64(v: Long) = be32(v ushr 32) + be32(v and 0xFFFFFFFFL)

internal fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

internal fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

internal fun le32(v: Int) = le32(v.toLong())

internal fun le64(v: Long) = le32(v and 0xFFFFFFFFL) + le32(v ushr 32)

internal fun syncsafe(v: Int) = byteArrayOf(
    (v shr 21 and 0x7F).toByte(),
    (v shr 14 and 0x7F).toByte(),
    (v shr 7 and 0x7F).toByte(),
    (
        v and
            0x7F
        ).toByte(),
)

internal fun join(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { a, b -> a + b }

/** A JPEG's first bytes, enough to tell pictures apart. */
internal fun picture(mark: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), mark.toByte(), 1, 2, 3)

internal object Id3Files {
    fun tag(major: Int, vararg frames: ByteArray, flags: Int = 0, padding: Int = 32): ByteArray {
        val body = join(*frames, ByteArray(padding))
        return join(ascii("ID3"), byteArrayOf(major.toByte(), 0, flags.toByte()), syncsafe(body.size), body)
    }

    fun frame(major: Int, id: String, data: ByteArray, plainSize: Boolean = false): ByteArray = when (major) {
        2 -> join(ascii(id), be24(data.size), data)
        3 -> join(ascii(id), be32(data.size), byteArrayOf(0, 0), data)
        else -> join(ascii(id), if (plainSize) be32(data.size) else syncsafe(data.size), byteArrayOf(0, 0), data)
    }

    fun text(encoding: Int, bytes: ByteArray) = join(byteArrayOf(encoding.toByte()), bytes)

    /** APIC with a Latin-1 description. */
    fun apic(type: Int, image: ByteArray, description: String = "") =
        join(byteArrayOf(0), ascii("image/jpeg"), byteArrayOf(0, type.toByte()), ascii(description), byteArrayOf(0), image)

    fun v1(title: ByteArray, artist: ByteArray, album: ByteArray): ByteArray {
        fun field(b: ByteArray) = b.copyOf(30)
        return join(ascii("TAG"), field(title), field(artist), field(album), ascii("2001"), ByteArray(30), byteArrayOf(17))
    }
}

internal object MpegFiles {
    /** MPEG-1 Layer III, 44.1 kHz, 128 kbit/s, stereo: 417 bytes a frame, 1152 samples. */
    private val HEADER = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
    const val FRAME = 417

    fun frames(count: Int): ByteArray = join(*Array(count) { join(HEADER, ByteArray(FRAME - 4)) })

    /** A Xing header in the first frame counting [frames] frames, then two plain frames. */
    fun xing(frames: Int): ByteArray {
        val first = join(HEADER, ByteArray(32), ascii("Xing"), be32(1), be32(frames)).copyOf(FRAME)
        return join(first, frames(2))
    }
}

internal object Mp4Files {
    fun box(type: String, vararg content: ByteArray): ByteArray {
        val body = join(*content)
        return join(be32(8 + body.size), latin1(type), body)
    }

    fun data(type: Int, value: ByteArray) = box("data", be32(type), be32(0), value)

    /** `ftyp`, the audio, then `moov` at the end (as many encoders write it). */
    fun song(title: String, artist: String, cover: ByteArray, seconds: Long): ByteArray {
        val mvhd = box("mvhd", ByteArray(4), ByteArray(8), be32(1000), be32(seconds * 1000), ByteArray(80))
        val ilst = box(
            "ilst",
            box("©nam", data(1, utf8(title))),
            box("©ART", data(1, utf8(artist))),
            box("covr", data(13, cover)),
        )
        val meta = box("meta", ByteArray(4), box("hdlr", ByteArray(25)), ilst)
        return join(box("ftyp", ascii("M4A "), be32(0)), box("mdat", ByteArray(5000)), box("moov", mvhd, box("udta", meta)))
    }
}

internal object VorbisFiles {
    fun comments(vararg pairs: String): ByteArray =
        join(le32(6), ascii("Benbow"), le32(pairs.size), *pairs.map { join(le32(utf8(it).size), utf8(it)) }.toTypedArray())

    fun flacPicture(type: Int, image: ByteArray): ByteArray =
        join(be32(type), be32(10), ascii("image/jpeg"), be32(0), ByteArray(16), be32(image.size), image)

    fun flac(rate: Long, samples: Long, comments: ByteArray, picture: ByteArray): ByteArray {
        val info = join(ByteArray(10), be64((rate shl 44) or (1L shl 41) or (15L shl 36) or samples))
        fun block(type: Int, last: Boolean, body: ByteArray) =
            join(byteArrayOf((type or if (last) 0x80 else 0).toByte()), be24(body.size), body)
        return join(ascii("fLaC"), block(0, false, info), block(4, false, comments), block(6, true, picture), ByteArray(2000))
    }

    fun page(serial: Int, sequence: Int, granule: Long, lacing: IntArray, body: ByteArray, type: Int = 0): ByteArray = join(
        ascii("OggS"),
        byteArrayOf(0, type.toByte()),
        le64(granule),
        le32(serial),
        le32(sequence),
        ByteArray(4),
        byteArrayOf(lacing.size.toByte()),
        ByteArray(lacing.size) { lacing[it].toByte() },
        body,
    )

    /** Opus with its tags over two pages (a packet longer than one segment run) and [seconds] of audio. */
    fun opus(seconds: Long, vararg pairs: String): ByteArray {
        val head = join(ascii("OpusHead"), byteArrayOf(1, 2), le16(312), le32(48_000), byteArrayOf(0, 0, 0))
        val tags = join(ascii("OpusTags"), comments(*pairs, "COMMENT=" + "x".repeat(300)))
        check(tags.size in 256..509)
        val first = tags.copyOf(255)
        val rest = tags.copyOfRange(255, tags.size)
        return join(
            page(7, 0, 0, intArrayOf(head.size), head, type = 2),
            page(7, 1, 0, intArrayOf(255), first),
            page(7, 2, 0, intArrayOf(rest.size), rest, type = 1),
            page(7, 3, 312 + seconds * 48_000, intArrayOf(100), ByteArray(100), type = 4),
        )
    }
}

internal object RiffFiles {
    fun chunk(id: String, body: ByteArray, bigEndian: Boolean = false): ByteArray {
        val padded = if (body.size % 2 == 1) body + 0.toByte() else body
        return join(ascii(id), if (bigEndian) be32(body.size) else le32(body.size), padded)
    }

    /** 16-bit stereo at 44.1 kHz: 176,400 bytes a second. */
    fun wave(seconds: Int, title: ByteArray, artist: ByteArray): ByteArray {
        val fmt = join(le16(1), le16(2), le32(44_100), le32(176_400), le16(4), le16(16))
        val info = join(ascii("INFO"), chunk("INAM", title + 0.toByte()), chunk("IART", artist + 0.toByte()))
        val body = join(ascii("WAVE"), chunk("fmt ", fmt), chunk("LIST", info), chunk("data", ByteArray(176_400 * seconds)))
        return join(ascii("RIFF"), le32(body.size), body)
    }

    /** 44.1 kHz stereo; the sample rate as an 80-bit extended number. */
    fun aiff(frames: Long, title: String): ByteArray {
        val rate = byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0)
        val comm = join(be16(2), be32(frames), be16(16), rate)
        val body = join(ascii("AIFF"), chunk("COMM", comm, true), chunk("NAME", ascii(title), true), chunk("SSND", ByteArray(64), true))
        return join(ascii("FORM"), be32(body.size), body)
    }
}
