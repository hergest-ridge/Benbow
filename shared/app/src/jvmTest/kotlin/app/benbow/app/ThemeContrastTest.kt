package app.benbow.app

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import app.benbow.client.SWATCHES
import app.benbow.ui.theme.BenbowColors
import app.benbow.ui.theme.BenbowPalette
import app.benbow.ui.theme.OwnPalette
import app.benbow.ui.theme.PaletteCharacter
import app.benbow.ui.theme.PaletteGround
import app.benbow.ui.theme.SwatchColors
import app.benbow.ui.theme.ThemePalette
import app.benbow.ui.theme.ThemeSpec
import app.benbow.ui.theme.colorsOf
import app.benbow.ui.theme.contrastRatio
import app.benbow.ui.theme.hctColor
import app.benbow.ui.theme.hex
import app.benbow.ui.theme.lookBudget
import app.benbow.ui.theme.schemeOf
import app.benbow.ui.theme.screen
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every palette in both modes keeps the contrast ADR-057 promises (WCAG 2.2): a note's words 7:1, every other word
 * 4.5:1 on every ground it lies on, controls' edges and marks 3:1, and cards that stand off the page.
 */
class ThemeContrastTest {
    private class Theme(val name: String, val scheme: ColorScheme, val colors: BenbowColors) {
        val dark = colors.dark
        val fills = SWATCHES.map { colors.swatches.fill(it, dark)!! }

        /** Where words lie: the page, a card, a note's paper, a board card, an inset, and every note color. */
        val grounds =
            listOf(scheme.background, scheme.surfaceContainerLow, colors.paper, colors.boardCard, scheme.surfaceContainerHighest) + fills
        val pages = listOf(scheme.background, scheme.surfaceContainerLow, colors.paper)
    }

    private val presets = ThemePalette.entries.filter { it != ThemePalette.System && it != ThemePalette.Own }

    /** Palettes of one's own all round the wheel, pale and strong, in every character and on every ground. */
    private val owns = (0 until 360 step 30).flatMap { hue ->
        listOf(24.0, 64.0).flatMap { chroma ->
            PaletteCharacter.entries.map { character ->
                OwnPalette(hctColor(hue.toDouble(), chroma), character, PaletteGround.entries[hue / 30 % PaletteGround.entries.size])
            }
        }
    }

    private val levels = listOf(0.0, 0.5, 1.0)

    private val specs = presets.flatMap { palette -> levels.map { ThemeSpec(palette, contrast = it) } } +
        presets.map { ThemeSpec(it, black = true) } +
        owns.mapIndexed { i, own -> ThemeSpec(ThemePalette.Own, own, contrast = levels[i % levels.size]) }

    private val themes = specs.flatMap { spec ->
        listOf(false, true).filter { dark -> dark || !spec.black }.map { dark ->
            val own = spec.own?.let { " ${it.seed.hex()} ${it.character} ${it.ground}" }.orEmpty()
            val name = "${spec.palette}$own c${spec.contrast}${if (spec.black) " black" else ""} ${if (dark) "dark" else "light"}"
            Theme(name, schemeOf(spec, dark), colorsOf(spec, dark))
        }
    }

    private val failures = mutableListOf<String>()

    private fun expect(theme: Theme, what: String, words: Color, ground: Color, at: Double) {
        val ratio = contrastRatio(words.compositeOver(ground), ground)
        if (ratio < at) failures += "${theme.name}: $what %.2f < $at".format(ratio)
    }

    private fun report() = assertTrue(failures.isEmpty(), "${failures.size} failures:\n" + failures.take(40).joinToString("\n"))

    @Test
    fun wordsReadOnEveryGroundTheyLieOn() {
        themes.forEach { t ->
            t.pages.forEach { expect(t, "a note's words on its page", t.scheme.onSurface, it, 7.0) }
            t.grounds.forEach { ground ->
                expect(t, "words on ${ground.hex()}", t.scheme.onSurface, ground, 7.0)
                expect(t, "secondary words on ${ground.hex()}", t.scheme.onSurfaceVariant, ground, 4.5)
                expect(t, "quiet words on ${ground.hex()}", t.colors.textQuiet, ground, 4.5)
                expect(t, "a link on ${ground.hex()}", t.scheme.primary, ground, 4.5)
                BenbowPalette.tokens.forEach { token ->
                    expect(t, "$token words on ${ground.hex()}", t.colors.tokens.text(token, t.dark)!!, ground, 4.5)
                }
            }
            t.pages.forEach { page ->
                expect(t, "a link on ${page.hex()}", t.scheme.primary, page, 4.5)
                SWATCHES.forEach { expect(t, "a $it category's name", t.colors.swatches.mark(it, t.dark)!!, page, 4.5) }
            }
        }
        report()
    }

    @Test
    fun wordsOnContainersButtonsAndMarkersRead() {
        themes.forEach { t ->
            val s = t.scheme
            expect(t, "on primary", s.onPrimary, s.primary, 4.5)
            expect(t, "on the primary container", s.onPrimaryContainer, s.primaryContainer, 7.0)
            expect(t, "on the secondary container", s.onSecondaryContainer, s.secondaryContainer, 7.0)
            expect(t, "on the tertiary container", s.onTertiaryContainer, s.tertiaryContainer, 7.0)
            expect(t, "on the error container", s.onErrorContainer, s.errorContainer, 7.0)
            t.pages.forEach { page ->
                BenbowPalette.tokens.forEach { token ->
                    val marked = t.colors.tokens.highlight(token, t.dark)!!.compositeOver(page)
                    expect(t, "words over a $token highlight", s.onSurface, marked, 7.0)
                    val callout = t.colors.tokens.tint(token, t.dark)!!.compositeOver(page)
                    expect(t, "words in a $token callout", s.onSurface, callout, 7.0)
                }
            }
        }
        report()
    }

    @Test
    fun edgesMarksAndCardsStandOffThePage() {
        themes.forEach { t ->
            t.pages.forEach { page ->
                expect(t, "a field's outline", t.scheme.outline, page, 3.0)
                SWATCHES.forEach { expect(t, "a $it dot", t.colors.swatches.mark(it, t.dark)!!, page, 3.0) }
            }
            val page = t.scheme.background
            // White cards on a tinted page (owner): in the light a card is lighter than its page by a step one sees.
            if (!t.dark) {
                expect(t, "a card on the page", t.scheme.surfaceContainerLow, page, 1.15)
                expect(t, "a board card on the page", t.colors.boardCard, page, 1.15)
                expect(t, "a divider on the page", t.colors.divider, page, 1.3)
                expect(t, "an inset in a card", t.scheme.surfaceContainerHigh, t.scheme.surfaceContainerLow, 1.15)
            }
            t.fills.forEach { expect(t, "a note color on the page", it, page, if (t.dark) 1.6 else 1.08) }
        }
        report()
    }

    @Test
    fun noTwoNoteColorsOrTokensLookAlike() {
        themes.forEach { t ->
            pairs(t.fills, SWATCHES).forEach { (a, b, names) ->
                if (distance(a, b) <
                    8.0
                ) {
                    failures += "${t.name}: $names fills %.1f".format(distance(a, b))
                }
            }
            val inks = BenbowPalette.tokens.map { t.colors.tokens.text(it, t.dark)!! }
            pairs(inks, BenbowPalette.tokens).forEach { (a, b, names) ->
                if (distance(a, b) <
                    8.0
                ) {
                    failures += "${t.name}: $names words %.1f".format(distance(a, b))
                }
            }
        }
        report()
    }

    @Test
    fun aLookNeverTakesTheWordsBelowTheirContrast() {
        themes.forEach { t ->
            (t.fills + t.colors.paper).forEach { base ->
                BenbowPalette.tokens.forEach { token ->
                    val layer = t.colors.tokens.mark(token, t.dark)!!
                    val over = { alpha: Float -> if (t.dark) screen(layer, alpha, base) else layer.copy(alpha = alpha).compositeOver(base) }
                    listOf(0.18f, 0.3f, 0.45f).forEach { wanted ->
                        val ground = over(lookBudget(wanted, t.scheme.onSurface, t.scheme.onSurfaceVariant, over))
                        expect(t, "words under a $token light", t.scheme.onSurface, ground, 7.0)
                        expect(t, "secondary words under a $token light", t.scheme.onSurfaceVariant, ground, 4.5)
                    }
                }
            }
        }
        report()
    }

    @Test
    fun theThemeKnowsEveryNoteColorTheDataAllows() = assertEquals(SWATCHES, SwatchColors.names)

    private fun pairs(colors: List<Color>, names: List<String>) = colors.indices.flatMap { i ->
        (i + 1 until colors.size).map { j -> Triple(colors[i], colors[j], "${names[i]}/${names[j]}") }
    }

    /** CIE76 ΔE: how far apart two colors look (about 2 is just noticeable). */
    private fun distance(a: Color, b: Color): Double {
        val x = a.convert(ColorSpaces.CieLab)
        val y = b.convert(ColorSpaces.CieLab)
        val (dl, da, db) = Triple(x.red - y.red, x.green - y.green, x.blue - y.blue)
        return sqrt((dl * dl + da * da + db * db).toDouble())
    }

    private fun Color.hex() = "#%06X".format(toArgb() and 0xFFFFFF)
}
