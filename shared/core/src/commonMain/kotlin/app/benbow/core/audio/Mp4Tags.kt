package app.benbow.core.audio

/**
 * MP4 audio (m4a, aac in MP4, alac): iTunes-style tags in `moov/udta/meta/ilst` and the length in `moov/mvhd`. The
 * `moov` box may come after the audio, so top-level boxes are skipped by their headers until it shows up.
 */
internal object Mp4Tags {
    suspend fun read(file: RandomRead): TrackInfo? {
        var at = 0L
        while (at + 8 <= file.size) {
            val h = file.read(at, 16)
            if (h.size < 8) return null
            var size = h.u32be(0)
            var header = 8
            if (size == 1L && h.size >= 16) {
                size = h.u64be(8)
                header = 16
            } else if (size == 0L) {
                size = file.size - at
            }
            if (size < header) return null
            if (h.name(4) == "moov") {
                if (size - header > MAX_MOOV) return null
                return moov(file.read(at + header, (size - header).toInt()))
            }
            at += size
        }
        return null
    }

    private fun moov(bytes: ByteArray): TrackInfo {
        var duration: Long? = null
        var title: String? = null
        var artist: String? = null
        var band: String? = null
        var album: String? = null
        var cover: ByteArray? = null
        boxes(bytes, 0, bytes.size) { type, from, to ->
            when (type) {
                "mvhd" -> duration = mvhd(bytes, from, to)

                "udta" -> boxes(bytes, from, to) { inner, f, t ->
                    if (inner == "meta") {
                        // A full box (version and flags first) in iTunes files; QuickTime's has none.
                        val start = if (t - f >= 8 && bytes.name(f + 4) == "hdlr") f else f + 4
                        boxes(bytes, start, t) { list, lf, lt ->
                            if (list == "ilst") {
                                boxes(bytes, lf, lt) { item, itf, itt ->
                                    val data = data(bytes, itf, itt)
                                    if (data != null) {
                                        val (kind, df, dt) = data
                                        when (item) {
                                            NAME -> title = title ?: text(bytes, kind, df, dt)
                                            ARTIST -> artist = artist ?: text(bytes, kind, df, dt)
                                            ALBUM_ARTIST -> band = band ?: text(bytes, kind, df, dt)
                                            ALBUM -> album = album ?: text(bytes, kind, df, dt)
                                            "covr" -> if (cover == null && dt > df) cover = bytes.copyOfRange(df, dt)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return TrackInfo(TrackTags(title, artist ?: band, album), duration, cover)
    }

    /** Version 0: 32-bit times; version 1: 64-bit; the timescale sits between them and the duration. */
    private fun mvhd(bytes: ByteArray, from: Int, to: Int): Long? {
        val v1 = bytes.u8(from) == 1
        val scaleAt = from + if (v1) 20 else 12
        if (scaleAt + (if (v1) 12 else 8) > to) return null
        val scale = bytes.u32be(scaleAt)
        val units = if (v1) bytes.u64be(scaleAt + 4) else bytes.u32be(scaleAt + 4)
        if (scale <= 0 || units <= 0 || units == 0xFFFFFFFFL) return null
        return units * 1000 / scale
    }

    /** An item's `data` box: its type (1 UTF-8, 2 UTF-16, 13 JPEG, 14 PNG), a locale, then the value. */
    private fun data(bytes: ByteArray, from: Int, to: Int): Triple<Int, Int, Int>? {
        var found: Triple<Int, Int, Int>? = null
        boxes(bytes, from, to) { type, f, t ->
            if (found == null && type == "data" && t - f >= 8) found = Triple(bytes.u24be(f + 1), f + 8, t)
        }
        return found
    }

    private fun text(bytes: ByteArray, kind: Int, from: Int, to: Int): String? =
        if (kind == 2) TagText.utf16(bytes, from, to, bigEndian = true) else TagText.utf8(bytes, from, to)

    /** Children of a box between [from] and [to]: each one's type, and where its content starts and ends. */
    private inline fun boxes(bytes: ByteArray, from: Int, to: Int, each: (String, Int, Int) -> Unit) {
        var i = from
        while (i + 8 <= to) {
            var size = bytes.u32be(i)
            var header = 8
            if (size == 1L) {
                if (i + 16 > to) return
                size = bytes.u64be(i + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - i).toLong()
            }
            if (size < header || i + size > to) return
            each(bytes.name(i + 4), i + header, (i + size).toInt())
            i = (i + size).toInt()
        }
    }

    private const val NAME = "©nam"
    private const val ARTIST = "©ART"
    private const val ALBUM_ARTIST = "aART"
    private const val ALBUM = "©alb"

    /** A movie box past this is not a song's (covers are well under it). */
    private const val MAX_MOOV = 32L * 1024 * 1024
}
