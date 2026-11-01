package app.benbow.core.media

import kotlin.math.roundToInt

/**
 * How an attachment shows in its post (owner, 2026-10-30), wherever it stands (in the text, or among those outside it):
 * its [fit], what stays in sight when it is cropped ([focus]) and whether it plays by itself ([play], a GIF's or a
 * video's). Each is an LWW field of the entry of its own (`media_fit:<id>`, `media_focus:<id>`, `media_play:<id>`), so
 * options changed on two devices don't overwrite each other; the document never changes. A value this build doesn't
 * know reads as the default and stays in its field.
 */
data class MediaLook(val fit: Fit = Fit.Natural, val focus: Focus? = null, val play: Boolean? = null, val frame: String? = null) {
    /** [Natural]: its own shape (the owner's default); [Fill]: a post's frame, cropped around [focus]; [Whole]: that frame, all of it in sight. */
    enum class Fit(val token: String) { Natural("natural"), Fill("fill"), Whole("whole") }

    /** What stays in sight when cropped: a point across and down the picture, each 0 to 1. */
    data class Focus(val x: Float, val y: Float) {
        override fun toString() = "${part(x)},${part(y)}"

        private fun part(v: Float): String = ((v.coerceIn(0f, 1f) * 100).roundToInt() / 100.0).toString()
    }

    /** Whether it plays by itself: as chosen, else a GIF does and a video doesn't (owner). */
    fun plays(gif: Boolean): Boolean = play ?: gif

    /** Its scrapbook frame's kind ([FRAMES]) and how many times it was shuffled ("tape.7"), or null without one. */
    val frameKind: Pair<String, Int>? get() = frame?.let { token ->
        token.substringBefore('.').takeIf { it in FRAMES }?.let { it to (token.substringAfter('.', "").toIntOrNull() ?: 0) }
    }

    companion object {
        const val FIT = "media_fit:"
        const val FOCUS = "media_focus:"
        const val PLAY = "media_play:"
        const val FRAME = "media_frame:"

        /** A scrapbook's ways to hold a picture in the text (owner, 2026-10-30): tape on its corners, a strip, a polaroid, corners. */
        val FRAMES = listOf("tape", "strip", "polaroid", "corners")
        private const val AUTO = "auto"
        private const val TAP = "tap"

        /** Every attachment's look in an entry's [fields], by attachment id; those with none are left out. */
        fun of(fields: Map<String, String?>): Map<String, MediaLook> {
            val looks = HashMap<String, MediaLook>()
            for ((key, value) in fields) {
                if (value == null) continue
                when {
                    key.startsWith(FIT) -> Fit.entries.firstOrNull { it.token == value }?.let { fit ->
                        val id = key.removePrefix(FIT)
                        looks[id] = (looks[id] ?: MediaLook()).copy(fit = fit)
                    }

                    key.startsWith(FOCUS) -> focusOf(value)?.let { focus ->
                        val id = key.removePrefix(FOCUS)
                        looks[id] = (looks[id] ?: MediaLook()).copy(focus = focus)
                    }

                    key.startsWith(FRAME) -> value.takeIf { it.substringBefore('.') in FRAMES }?.let { frame ->
                        val id = key.removePrefix(FRAME)
                        looks[id] = (looks[id] ?: MediaLook()).copy(frame = frame)
                    }

                    key.startsWith(PLAY) -> when (value) {
                        AUTO -> true
                        TAP -> false
                        else -> null
                    }?.let { play ->
                        val id = key.removePrefix(PLAY)
                        looks[id] = (looks[id] ?: MediaLook()).copy(play = play)
                    }
                }
            }
            return looks
        }

        /** The field values for [fit] ([Fit.Natural] is no value), [focus] and [play] of attachment [id]. */
        fun fitField(id: String, fit: Fit): Pair<String, String?> = FIT + id to fit.token.takeIf { fit != Fit.Natural }

        fun focusField(id: String, focus: Focus?): Pair<String, String?> = FOCUS + id to focus?.toString()

        fun playField(id: String, play: Boolean?): Pair<String, String?> = PLAY + id to play?.let { if (it) AUTO else TAP }

        fun frameField(id: String, frame: String?): Pair<String, String?> = FRAME + id to frame

        private fun focusOf(value: String): Focus? {
            val (x, y) = value.split(',').takeIf { it.size == 2 }?.map { it.trim().toFloatOrNull() } ?: return null
            if (x == null || y == null || x !in 0f..1f || y !in 0f..1f) return null
            return Focus(x, y)
        }
    }
}
