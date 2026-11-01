package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import app.benbow.client.Media
import app.benbow.client.randomRead
import app.benbow.ui.theme.BenbowTheme
import app.benbow.ui.theme.HueToken
import com.materialkolor.hct.Hct
import com.materialkolor.ktx.quantize
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * A note's light from its pictures (owner, 2026-10-30: «Свет из вложений»): `look_light` names how, never the colors,
 * so new pictures bring new light. "from:vibrant" is the most fitting bright color as Material You picks one from a
 * wallpaper (Score over the Celebi quantizer), "from:palette" the picture's main colors that differ in hue, each a light
 * of its own, "from:cover" the picture itself blurred behind the words (a tiny copy drawn large). The colors take the
 * theme's tone as colors of one's own do (HueToken), so the words keep their contrast by construction.
 */

/** The app's media, for what draws without the services at hand (a note's light from its pictures). */
internal val LocalMediaStore = staticCompositionLocalOf<Media?> { null }

/** What one picture's colors say for a light: its bright colors ranked, its main ones, and a tiny copy of it. */
internal class PictureColors(val vibrant: List<Color>, val palette: List<Color>, val tiny: ImageBitmap)

internal object PictureLights {
    const val PREFIX = "from:"
    val kinds = listOf("vibrant", "palette", "cover")

    private val read = HashMap<String, PictureColors>()

    fun cached(sha256: String): PictureColors? = read[sha256]

    /** [sha256]'s colors, counted once (a small copy is enough: the colors are the same, the counting much quicker). */
    suspend fun of(media: Media, sha256: String): PictureColors? = read[sha256] ?: try {
        val file = media.localFile(sha256) ?: media.file(sha256)
        withContext(Dispatchers.Default) {
            val bytes = file.randomRead().let { it.read(0, it.size.toInt()) }
            val image = bytes.decodeToImageBitmap()
            val small = ImageBitmap(SMALL, SMALL).also { Canvas(it).drawImageRect(image, dstSize = IntSize(SMALL, SMALL), paint = Paint()) }
            val counted = QuantizerCelebi.quantize(small, MAX_COLORS)
            val vibrant = Score.score(counted, desired = 2, fallbackColorArgb = null, filter = true).map(::Color)
            val tiny = ImageBitmap(TINY, TINY).also { Canvas(it).drawImageRect(image, dstSize = IntSize(TINY, TINY), paint = Paint()) }
            PictureColors(vibrant, mainColors(counted), tiny)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }?.also { read[sha256] = it }

    /** The picture's most common colors that aren't grey, each at least a sixth of the wheel from those before it. */
    private fun mainColors(counted: Map<Int, Int>): List<Color> {
        val chosen = ArrayList<Hct>()
        for ((argb, _) in counted.entries.sortedByDescending { it.value }) {
            val hct = Hct.fromInt(argb)
            if (hct.chroma < MIN_CHROMA) continue
            if (chosen.any { apart(it.hue, hct.hue) < MIN_APART }) continue
            chosen += hct
            if (chosen.size == 3) break
        }
        return chosen.map { Color(it.toInt()) }
    }

    private fun apart(a: Double, b: Double): Double = abs(((a - b) % 360 + 540) % 360 - 180)

    private const val SMALL = 64
    private const val TINY = 24
    private const val MAX_COLORS = 16
    private const val MIN_CHROMA = 8.0
    private const val MIN_APART = 60.0
}

/**
 * [look] with its light from [picture] when it asks for one ([NoteLook.lightFrom]): lights in the picture's colors at
 * the theme's tone, or the picture itself blurred; as it was otherwise (and while the picture is read, or without one).
 */
@Composable
internal fun pictureLook(look: NoteLook, picture: String?): NoteLook {
    val kind = look.lightFrom ?: return look
    val media = LocalMediaStore.current
    if (picture == null || media == null) return look
    val colors by produceState(PictureLights.cached(picture), picture) { value = PictureLights.of(media, picture) }
    val dark = BenbowTheme.colors.dark
    return remember(look, colors, dark) {
        val read = colors ?: return@remember look
        fun tone(c: Color) = Hct.fromInt(c.toArgb()).let {
            HueToken(it.hue.roundToInt() % 360, it.chroma.roundToInt().coerceIn(0, HueToken.MAX_CHROMA)).mark(dark)
        }
        when (kind) {
            "vibrant" -> look.copy(
                lights = read.vibrant.take(2).mapIndexed { i, c ->
                    LookLight(
                        if (i ==
                            0
                        ) {
                            315f
                        } else {
                            135f
                        },
                        "",
                        if (i == 0) 3 else 1,
                        tone(c),
                    )
                },
            )

            "palette" -> look.copy(
                lights = read.palette.mapIndexed { i, c -> LookLight(PALETTE_ANGLES[i], "", if (i < 2) 2 else 1, tone(c)) },
            )

            else -> look.copy(cover = read.tiny)
        }
    }
}

/** Where a palette's lights come from, as «Три света» has them. */
private val PALETTE_ANGLES = listOf(200f, 330f, 80f)
