package app.benbow.core.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a sound file says it is (ADR-035): kept in the entry field `track:<attachment id>` as canonical JSON with only
 * the names it has, written once when the file is attached.
 */
@Serializable
data class TrackTags(val title: String? = null, val artist: String? = null, val album: String? = null) {
    val isEmpty: Boolean get() = title == null && artist == null && album == null

    /** A song rather than a recording: it names an artist (a recorder may name its file, never a performer). */
    val isMusic: Boolean get() = artist != null

    /** Each name from here, else from [other]. */
    fun orElse(other: TrackTags): TrackTags = TrackTags(title ?: other.title, artist ?: other.artist, album ?: other.album)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FIELD_PREFIX = "track:"

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun decode(value: String?): TrackTags? =
            value?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }?.takeUnless { it.isEmpty }

        /** Each sound's names among an entry's fields, by attachment id. */
        fun of(fields: Map<String, String?>): Map<String, TrackTags> = fields.mapNotNull { (key, value) ->
            if (key.startsWith(FIELD_PREFIX)) decode(value)?.let { key.removePrefix(FIELD_PREFIX) to it } else null
        }.toMap()
    }
}

/** Tags, length and the embedded cover (the image file's bytes, JPEG or PNG). */
class TrackInfo(val tags: TrackTags = TrackTags(), val durationMs: Long? = null, val cover: ByteArray? = null)

/**
 * Reads a sound's tags the same way on every platform: ID3v2 and ID3v1 (mp3, also in front of FLAC or AAC), MP4
 * (m4a), FLAC, Ogg Vorbis and Opus, WAV and AIFF. Never throws: what it can't read is simply missing.
 */
object TrackReader {
    suspend fun read(file: RandomRead): TrackInfo = try {
        readOrThrow(file)
    } catch (e: CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        TrackInfo() // a broken or cut-off file: bounds and sizes that don't add up
    }

    private suspend fun readOrThrow(file: RandomRead): TrackInfo {
        if (file.size < 4) return TrackInfo()
        val head = file.read(0, HEAD)
        val tagLength = Id3.length(head)
        val id3 = tagLength?.let {
            if (it <=
                head.size
            ) {
                head.copyOf(it)
            } else if (it <= MAX_TAG) {
                file.read(0, it)
            } else {
                null
            }
        }?.let(Id3::parse)
        val start = tagLength?.toLong() ?: 0L
        val rest = if (start == 0L) head else file.read(start, HEAD)
        val body = when {
            rest.has("fLaC") -> VorbisTags.flac(file, start)
            rest.has("OggS") -> VorbisTags.ogg(file, start)
            rest.has("RIFF") && rest.has("WAVE", 8) -> RiffTags.wave(file, start)
            rest.has("FORM") && (rest.has("AIFF", 8) || rest.has("AIFC", 8)) -> RiffTags.aiff(file, start)
            start == 0L && rest.has("ftyp", 4) -> Mp4Tags.read(file)
            else -> null
        }
        if (body != null) return TrackInfo(body.tags.orElse(id3?.tags ?: TrackTags()), body.durationMs, body.cover ?: id3?.cover)
        // MPEG audio, with an ID3v1 tag perhaps in its last 128 bytes.
        val last = if (file.size >= 128 + start) file.read(file.size - 128, 128) else ByteArray(0)
        val v1 = Id3.v1(last)
        val end = file.size - if (v1 != null || last.has("TAG")) 128 else 0
        val tags = (id3?.tags ?: TrackTags()).orElse(v1 ?: TrackTags())
        return TrackInfo(tags, MpegAudio.durationMs(rest, start, start, end), id3?.cover)
    }

    private const val HEAD = 64 * 1024

    /** An ID3v2 tag past this (a huge picture) is given up on. */
    private const val MAX_TAG = 16 * 1024 * 1024
}
