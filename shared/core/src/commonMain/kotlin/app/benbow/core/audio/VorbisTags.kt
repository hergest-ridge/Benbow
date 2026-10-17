package app.benbow.core.audio

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * FLAC and Ogg (Vorbis, Opus): both carry Vorbis comments (`TITLE=…`, UTF-8) and FLAC's picture block, which Ogg
 * files hold base64-encoded in `METADATA_BLOCK_PICTURE`.
 */
internal object VorbisTags {
    /** FLAC's metadata blocks after `fLaC` at [start]: STREAMINFO (the length), comments, pictures. */
    suspend fun flac(file: RandomRead, start: Long): TrackInfo {
        var at = start + 4
        var duration: Long? = null
        var found = Comments()
        while (at + 4 <= file.size) {
            val h = file.read(at, 4)
            if (h.size < 4) break
            val last = h.u8(0) and 0x80 != 0
            val type = h.u8(0) and 0x7F
            val length = h.u24be(1)
            if (type == STREAMINFO || type == COMMENT || type == PICTURE) {
                val block = file.read(at + 4, length)
                when (type) {
                    STREAMINFO -> if (block.size >= 18) {
                        val rate = (block.u24be(10) shr 4).toLong()
                        val samples = ((block.u8(13) and 0x0F).toLong() shl 32) or block.u32be(14)
                        if (rate > 0 && samples > 0) duration = samples * 1000 / rate
                    }

                    COMMENT -> found = comments(block, 0, found)

                    PICTURE -> picture(block)?.let { (kind, bytes) -> found.picture(kind, bytes) }
                }
            }
            at += 4 + length
            if (last) break
        }
        return TrackInfo(found.tags, duration, found.cover)
    }

    /**
     * Ogg: the first packet names the codec (Vorbis's sample rate; Opus counts at 48 kHz less a pre-skip), the second
     * holds the comments, often over many pages when a cover is inside. The last page's granule position gives the length.
     */
    suspend fun ogg(file: RandomRead, start: Long): TrackInfo? {
        val packets = mutableListOf<ByteArray>()
        var packet = Bytes()
        var at = start
        var serial: Long? = null
        var read = 0L
        while (packets.size < 2 && at + 27 <= file.size && read < MAX_HEADERS) {
            val h = file.read(at, 27 + 255)
            if (h.size < 27 || !h.has("OggS")) break
            val count = h.u8(26)
            if (h.size < 27 + count) break
            val page = h.u32le(14)
            val lacing = IntArray(count) { h.u8(27 + it) }
            val body = file.read(at + 27 + count, lacing.sum())
            at += 27 + count + lacing.sum()
            read += 27 + count + body.size
            if (serial == null) serial = page
            if (page != serial) continue // another stream interleaved
            var offset = 0
            for (segment in lacing) {
                packet.add(body, offset, minOf(body.size, offset + segment))
                offset += segment
                if (segment < 255) {
                    packets += packet.bytes()
                    packet = Bytes()
                    if (packets.size == 2) break
                }
            }
        }
        val id = packets.firstOrNull() ?: return null
        val (rate, skip, tags) = when {
            id.has("vorbis", 1) && id.size >= 16 -> Triple(
                id.u32le(12),
                0L,
                packets.getOrNull(1)?.takeIf {
                    it.has("vorbis", 1)
                }?.let { 7 },
            )

            id.has("OpusHead") && id.size >= 12 -> Triple(
                48_000L,
                id.u16le(10).toLong(),
                packets.getOrNull(1)?.takeIf {
                    it.has("OpusTags")
                }?.let { 8 },
            )

            else -> return null
        }
        val found = tags?.let { comments(packets[1], it, Comments()) } ?: Comments()
        return TrackInfo(
            found.tags,
            lastGranule(file, serial)?.let { g ->
                if (rate > 0 &&
                    g > skip
                ) {
                    (g - skip) * 1000 / rate
                } else {
                    null
                }
            },
            found.cover,
        )
    }

    /** The granule position of the stream's last page, from the file's last 64 KB. */
    private suspend fun lastGranule(file: RandomRead, serial: Long?): Long? {
        val from = maxOf(0L, file.size - TAIL)
        val tail = file.read(from, (file.size - from).toInt())
        var i = tail.size - 27
        while (i >= 0) {
            if (tail.has("OggS", i) && (serial == null || tail.u32le(i + 14) == serial)) {
                val granule = tail.u64le(i + 6)
                if (granule > 0) return granule
            }
            i--
        }
        return null
    }

    /** A vendor string, then `KEY=value` pairs, all with 32-bit little-endian lengths. */
    private fun comments(bytes: ByteArray, from: Int, into: Comments): Comments {
        var i = from
        if (i + 4 > bytes.size) return into
        i += 4 + bytes.u32le(i).toInt()
        if (i + 4 > bytes.size || i < 0) return into
        val count = bytes.u32le(i)
        i += 4
        var n = 0L
        while (n < count && i + 4 <= bytes.size) {
            val length = bytes.u32le(i).toInt()
            i += 4
            if (length < 0 || i + length > bytes.size) break
            val eq = bytes.zeroOr(i, i + length, '='.code)
            val key = bytes.name(i, eq - i).uppercase()
            when (key) {
                "TITLE" -> into.title = into.title ?: TagText.utf8(bytes, eq + 1, i + length)

                "ARTIST" -> TagText.utf8(bytes, eq + 1, i + length)?.let { into.artists += it }

                "ALBUMARTIST", "ALBUM ARTIST" -> into.band = into.band ?: TagText.utf8(bytes, eq + 1, i + length)

                "ALBUM" -> into.album = into.album ?: TagText.utf8(bytes, eq + 1, i + length)

                "METADATA_BLOCK_PICTURE" -> base64(bytes, eq + 1, i + length)?.let(::picture)?.let { (kind, data) ->
                    into.picture(kind, data)
                }

                "COVERART" -> base64(bytes, eq + 1, i + length)?.let { into.picture(OTHER, it) }
            }
            i += length
            n++
        }
        return into
    }

    /** FLAC's picture block: type, MIME type, description, size and colors, then the image. */
    private fun picture(block: ByteArray): Pair<Int, ByteArray>? {
        if (block.size < 32) return null
        val type = block.u32be(0).toInt()
        var i = 4
        i += 4 + block.u32be(i).toInt() // MIME type
        if (i + 4 > block.size || i < 0) return null
        i += 4 + block.u32be(i).toInt() // description
        if (i + 20 > block.size || i < 0) return null
        i += 16 // width, height, depth, colors
        val length = block.u32be(i).toInt()
        i += 4
        if (length <= 0 || i + length > block.size) return null
        return type to block.copyOfRange(i, i + length)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64(bytes: ByteArray, from: Int, to: Int): ByteArray? =
        runCatching { Base64.decode(bytes.decodeToString(from, to).filterNot { it.isWhitespace() }) }.getOrNull()

    private fun ByteArray.zeroOr(from: Int, to: Int, byte: Int): Int {
        for (i in from until to) if (u8(i) == byte) return i
        return to
    }

    private class Comments {
        var title: String? = null
        val artists = mutableListOf<String>()
        var band: String? = null
        var album: String? = null
        var cover: ByteArray? = null
        private var front = false

        val tags: TrackTags get() = TrackTags(title, artists.distinct().joinToString(", ").ifEmpty { null } ?: band, album)

        fun picture(type: Int, bytes: ByteArray) {
            if (bytes.isEmpty() || front) return
            if (cover == null || type == FRONT_COVER) cover = bytes
            front = type == FRONT_COVER
        }
    }

    /** A packet put together from its page segments. */
    private class Bytes {
        private var buffer = ByteArray(256)
        private var size = 0

        fun add(from: ByteArray, start: Int, end: Int) {
            val n = end - start
            if (n <= 0) return
            if (size + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + n))
            from.copyInto(buffer, size, start, end)
            size += n
        }

        fun bytes(): ByteArray = buffer.copyOf(size)
    }

    private const val STREAMINFO = 0
    private const val COMMENT = 4
    private const val PICTURE = 6
    private const val FRONT_COVER = 3
    private const val OTHER = 0
    private const val TAIL = 64 * 1024L

    /** Comment headers past this are given up on (a cover inside them is a few MB at most). */
    private const val MAX_HEADERS = 16L * 1024 * 1024
}
