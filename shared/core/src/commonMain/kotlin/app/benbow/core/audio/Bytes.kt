package app.benbow.core.audio

// Reading numbers and names out of a sound file's headers. Every format here mixes byte orders, so each reader says
// which one it means.

/** A file read in pieces: a sound's tags sit at its start or end, and its audio is never read whole. */
interface RandomRead {
    val size: Long

    /** Up to [length] bytes from [offset]; fewer at the end of the file. */
    suspend fun read(offset: Long, length: Int): ByteArray
}

/** Bytes already in memory (tests, small files). */
class BytesRead(private val bytes: ByteArray) : RandomRead {
    override val size: Long get() = bytes.size.toLong()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (offset >= bytes.size) return ByteArray(0)
        val from = offset.toInt()
        return bytes.copyOfRange(from, minOf(bytes.size, from + length))
    }
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

internal fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

internal fun ByteArray.u24be(i: Int): Int = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)

internal fun ByteArray.u32be(i: Int): Long =
    (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

internal fun ByteArray.u32le(i: Int): Long =
    u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)

internal fun ByteArray.u64be(i: Int): Long = (u32be(i) shl 32) or u32be(i + 4)

internal fun ByteArray.u64le(i: Int): Long = u32le(i) or (u32le(i + 4) shl 32)

/** ID3v2's sizes: seven bits per byte, so no byte looks like a frame sync. */
internal fun ByteArray.syncsafe(i: Int): Int =
    ((u8(i) and 0x7F) shl 21) or ((u8(i + 1) and 0x7F) shl 14) or ((u8(i + 2) and 0x7F) shl 7) or (u8(i + 3) and 0x7F)

/** Four-letter names (box and chunk types) as they are, a byte per character. */
internal fun ByteArray.name(i: Int, n: Int = 4): String = buildString(n) { for (k in 0 until n) append(Char(u8(i + k))) }

internal fun ByteArray.has(text: String, at: Int = 0): Boolean {
    if (at < 0 || at + text.length > size) return false
    return text.indices.all { u8(at + it) == text[it].code }
}

/** The first zero byte (or pair of them, for UTF-16) from [from], else [to]. */
internal fun ByteArray.zero(from: Int, to: Int, wide: Boolean = false): Int {
    if (!wide) {
        for (i in from until to) if (this[i].toInt() == 0) return i
        return to
    }
    var i = from
    while (i + 1 < to) {
        if (this[i].toInt() == 0 && this[i + 1].toInt() == 0) return i
        i += 2
    }
    return to
}
