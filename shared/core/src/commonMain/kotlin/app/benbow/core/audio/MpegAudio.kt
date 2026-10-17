package app.benbow.core.audio

/**
 * The length of MPEG audio (mp3, and mp2/mp1): the first frame's Xing/Info or VBRI header counts the frames of a
 * variable bitrate file; without one the bitrate is constant and the size tells.
 */
internal object MpegAudio {
    /**
     * Looks for the first frame in [window] (the file's bytes from [windowStart]) from [audioStart] on; the audio ends
     * at [audioEnd] (before an ID3v1 tag).
     */
    fun durationMs(window: ByteArray, windowStart: Long, audioStart: Long, audioEnd: Long): Long? {
        var i = (audioStart - windowStart).toInt().coerceAtLeast(0)
        while (i + 4 <= window.size) {
            if (window.u8(i) == 0xFF && window.u8(i + 1) and 0xE0 == 0xE0) {
                val frame = Frame.at(window, i)
                if (frame != null && frame.followedBy(window, i)) {
                    frame.counted(window, i)?.let { return it }
                    val bytes = audioEnd - (windowStart + i)
                    return if (bytes > 0) bytes * 8 / frame.kbps else null
                }
            }
            i++
        }
        return null
    }

    private class Frame(val version: Int, val layer: Int, val kbps: Int, val rate: Int, val padding: Int, val mono: Boolean) {
        val samples: Int get() = when (layer) {
            LAYER_1 -> 384
            LAYER_2 -> 1152
            else -> if (version == MPEG_1) 1152 else 576
        }

        val length: Int get() = when {
            layer == LAYER_1 -> (12 * kbps * 1000 / rate + padding) * 4
            layer == LAYER_3 && version != MPEG_1 -> 72 * kbps * 1000 / rate + padding
            else -> 144 * kbps * 1000 / rate + padding
        }

        /** A real frame is followed by another of the same kind (unless the window ends first). */
        fun followedBy(window: ByteArray, at: Int): Boolean {
            val next = at + length
            if (next + 2 > window.size) return true
            return window.u8(next) == 0xFF && window.u8(next + 1) and 0xFE == window.u8(at + 1) and 0xFE
        }

        /** The frame count of a Xing/Info or VBRI header, as a length. */
        fun counted(window: ByteArray, at: Int): Long? {
            val side = if (version == MPEG_1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
            val xing = at + 4 + side
            val frames = when {
                (window.has("Xing", xing) || window.has("Info", xing)) && xing + 12 <= window.size && window.u32be(xing + 4) and 1L != 0L ->
                    window.u32be(xing + 8)

                window.has("VBRI", at + 36) && at + 54 <= window.size -> window.u32be(at + 50)

                else -> return null
            }
            return if (frames > 0) frames * samples * 1000 / rate else null
        }

        companion object {
            fun at(b: ByteArray, i: Int): Frame? {
                val version = (b.u8(i + 1) shr 3) and 3
                val layer = (b.u8(i + 1) shr 1) and 3
                val bitrate = b.u8(i + 2) shr 4
                val rateIndex = (b.u8(i + 2) shr 2) and 3
                if (version == 1 || layer == 0 || bitrate == 0 || bitrate == 15 || rateIndex == 3) return null
                val table = when {
                    version == MPEG_1 && layer == LAYER_1 -> V1_L1
                    version == MPEG_1 && layer == LAYER_2 -> V1_L2
                    version == MPEG_1 -> V1_L3
                    layer == LAYER_1 -> V2_L1
                    else -> V2_L23
                }
                val rate = RATES[rateIndex] / when (version) {
                    MPEG_1 -> 1
                    MPEG_2 -> 2
                    else -> 4
                }
                return Frame(version, layer, table[bitrate], rate, (b.u8(i + 2) shr 1) and 1, b.u8(i + 3) shr 6 == 3)
            }
        }
    }

    private const val MPEG_1 = 3
    private const val MPEG_2 = 2
    private const val LAYER_3 = 1
    private const val LAYER_2 = 2
    private const val LAYER_1 = 3
    private val RATES = intArrayOf(44_100, 48_000, 32_000)
    private val V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val V2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
}
