package app.benbow.core.audio

import kotlin.math.pow

/**
 * WAV (RIFF, little-endian) and AIFF (big-endian): chunks one after another, the audio's own skipped by its size.
 * WAV names a track in `LIST/INFO` (INAM, IART, IPRD), AIFF in NAME and AUTH; either may hold a whole ID3v2 tag.
 */
internal object RiffTags {
    suspend fun wave(file: RandomRead, start: Long): TrackInfo {
        var byteRate = 0L
        var data: Long? = null
        var info = TrackTags()
        var id3: Id3.Found? = null
        chunks(file, start + 12, bigEndian = false) { id, at, size ->
            when (id) {
                "fmt " -> file.read(at, 16).takeIf { it.size >= 12 }?.let { byteRate = it.u32le(8) }
                "data" -> data = if (size == 0xFFFFFFFFL || at + size > file.size) file.size - at else size
                "LIST" -> if (size <= MAX_CHUNK) info = info.orElse(listInfo(file.read(at, size.toInt())))
                "id3 ", "ID3 " -> if (size <= MAX_CHUNK) id3 = id3 ?: Id3.parse(file.read(at, size.toInt()))
            }
        }
        val duration = data?.takeIf { byteRate > 0 }?.let { it * 1000 / byteRate }
        return TrackInfo((id3?.tags ?: TrackTags()).orElse(info), duration, id3?.cover)
    }

    suspend fun aiff(file: RandomRead, start: Long): TrackInfo {
        var duration: Long? = null
        var title: String? = null
        var artist: String? = null
        var id3: Id3.Found? = null
        chunks(file, start + 12, bigEndian = true) { id, at, size ->
            when (id) {
                "COMM" -> file.read(at, 18).takeIf { it.size >= 18 }?.let { c ->
                    val frames = c.u32be(2)
                    val rate = extended(c, 8)
                    if (rate > 0) duration = (frames * 1000 / rate).toLong()
                }

                "NAME" -> if (size <= MAX_TEXT) title = title ?: file.read(at, size.toInt()).let { TagText.legacy(it, 0, it.size) }

                "AUTH" -> if (size <= MAX_TEXT) artist = artist ?: file.read(at, size.toInt()).let { TagText.legacy(it, 0, it.size) }

                "ID3 ", "id3 " -> if (size <= MAX_CHUNK) id3 = id3 ?: Id3.parse(file.read(at, size.toInt()))
            }
        }
        return TrackInfo((id3?.tags ?: TrackTags()).orElse(TrackTags(title, artist)), duration, id3?.cover)
    }

    /** Every chunk from [from] on: its id, where its content starts and its size (contents are padded to even sizes). */
    private suspend inline fun chunks(file: RandomRead, from: Long, bigEndian: Boolean, each: (String, Long, Long) -> Unit) {
        var at = from
        var count = 0
        while (at + 8 <= file.size && count++ < MAX_CHUNKS) {
            val h = file.read(at, 8)
            if (h.size < 8) return
            val size = if (bigEndian) h.u32be(4) else h.u32le(4)
            each(h.name(0), at + 8, size)
            if (size == 0xFFFFFFFFL) return
            at += 8 + size + (size and 1)
        }
    }

    /** `INFO` followed by subchunks of zero-ended text. */
    private fun listInfo(list: ByteArray): TrackTags {
        if (!list.has("INFO")) return TrackTags()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var i = 4
        while (i + 8 <= list.size) {
            val id = list.name(i)
            val size = list.u32le(i + 4).toInt()
            val from = i + 8
            if (size < 0 || from + size > list.size) break
            val text = TagText.legacy(list, from, list.zero(from, from + size))
            when (id) {
                "INAM" -> title = title ?: text
                "IART" -> artist = artist ?: text
                "IPRD" -> album = album ?: text
            }
            i = from + size + (size and 1)
        }
        return TrackTags(title, artist, album)
    }

    /** An 80-bit IEEE extended number (AIFF's sample rate). */
    private fun extended(bytes: ByteArray, at: Int): Double {
        val exponent = bytes.u16be(at) and 0x7FFF
        val mantissa = bytes.u64be(at + 2)
        if (exponent == 0 || mantissa == 0L) return 0.0
        return (mantissa ushr 11).toDouble() * 2.0.pow(exponent - 16383 - 52)
    }

    private const val MAX_CHUNK = 16L * 1024 * 1024
    private const val MAX_TEXT = 4096L
    private const val MAX_CHUNKS = 10_000
}
