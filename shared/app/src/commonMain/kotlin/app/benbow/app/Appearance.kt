package app.benbow.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.client.Settings
import app.benbow.client.ThemeChoice
import app.benbow.ui.theme.BenbowTheme
import app.benbow.ui.theme.OwnPalette
import app.benbow.ui.theme.PaletteCharacter
import app.benbow.ui.theme.PaletteGround
import app.benbow.ui.theme.ThemeContrast
import app.benbow.ui.theme.ThemeMode
import app.benbow.ui.theme.ThemePalette
import app.benbow.ui.theme.ThemeSpec
import app.benbow.ui.theme.colorOfHex
import app.benbow.ui.theme.hex
import app.benbow.ui.theme.isDark
import app.benbow.ui.theme.schemeOf

/** How the app looks on this device: light or dark, and the palette. Kept in local settings, not synced. */
data class Appearance(
    val mode: ThemeMode = ThemeMode.System,
    /** The palette this device had before it was one for every device (ADR-057): kept until a choice is made anywhere. */
    val palette: ThemePalette = ThemePalette.Inn,
    /** This device's contrast; null, its system's (ADR-057). */
    val contrast: ThemeContrast? = null,
    /** A dark theme on black, for this device's OLED screen. */
    val black: Boolean = false,
    /** Posts in the feed as rounded cards; off = edge to edge, media across the full width. */
    val feedCards: Boolean = true,
    /** The feed's date scrubber on its right edge (ADR-036 N3). */
    val feedScrubber: Boolean = true,
    /** How a post shows pictures that aren't placed in its text, when read. */
    val postMedia: PostMedia = PostMedia.Carousel,
    /** A link card reads its page once, as it goes in (ADR-038); off, cards show the address only. */
    val linkPreviews: Boolean = true,
    /** A link that is a picture brings it as an attachment under the link (ADR-045); off, the picture alone, no link. */
    val pictureLinks: Boolean = true,
    /** In the feed, a tap on a post's picture or video opens the post, not the viewer (owner: the web's switch). */
    val mediaOpensPost: Boolean = false,
    /** A list note's ticked boxes go under «Отмечено», as in Keep; off, they stay where they are, crossed out, as on paper. */
    val tickedBelow: Boolean = true,
    /** The web's: pictures, videos and galleries light up under the pointer (owner: off, they don't). */
    val mediaHover: Boolean = false,
    /** A wide window's open note: its light spreads over the whole right side, not only the note (owner: on). */
    val lightBeyondPage: Boolean = true,
    /** A wide window's open note: a thin line down each side, like the feed's edge (owner: on). */
    val pageBorders: Boolean = true,
    /** A note's paper, patterns and scrapbook stay put as its words scroll (owner: off, they go with the words, like paper). */
    val staticBackdrop: Boolean = false,
    /** «Шрифты записок в ленте» (owner): the feed's posts in their notes' own fonts, as they read open; off, the app's font. */
    val feedFonts: Boolean = false,
) {
    internal fun save(settings: Settings) {
        settings.put(MODE, mode.name)
        settings.put(PALETTE, palette.name)
        settings.put(CONTRAST, contrast?.name ?: "system")
        settings.put(BLACK, black.toString())
        settings.put(FEED_CARDS, feedCards.toString())
        settings.put(FEED_SCRUBBER, feedScrubber.toString())
        settings.put(POST_MEDIA, postMedia.name)
        settings.put(LINK_PREVIEWS, linkPreviews.toString())
        settings.put(PICTURE_LINKS, pictureLinks.toString())
        settings.put(MEDIA_OPENS_POST, mediaOpensPost.toString())
        settings.put(TICKED_BELOW, tickedBelow.toString())
        settings.put(MEDIA_HOVER, mediaHover.toString())
        settings.put(LIGHT_BEYOND_PAGE, lightBeyondPage.toString())
        settings.put(PAGE_BORDERS, pageBorders.toString())
        settings.put(STATIC_BACKDROP, staticBackdrop.toString())
        settings.put(FEED_FONTS, feedFonts.toString())
    }

    companion object {
        private const val MODE = "theme.mode"
        private const val PALETTE = "theme.palette"
        private const val CONTRAST = "theme.contrast"
        private const val BLACK = "theme.black"
        private const val FEED_CARDS = "feed.cards"
        private const val FEED_SCRUBBER = "feed.scrubber"
        private const val POST_MEDIA = "post.media"
        private const val LINK_PREVIEWS = "links.previews"
        private const val PICTURE_LINKS = "links.pictureLinks"
        private const val MEDIA_OPENS_POST = "feed.mediaOpensPost"
        private const val TICKED_BELOW = "board.tickedBelow"
        private const val MEDIA_HOVER = "media.hover"
        private const val LIGHT_BEYOND_PAGE = "post.lightBeyondPage"
        private const val PAGE_BORDERS = "post.borders"
        private const val STATIC_BACKDROP = "post.staticBackdrop"
        private const val FEED_FONTS = "feed.noteFonts"

        internal fun load(settings: Settings) = Appearance(
            mode = ThemeMode.entries.firstOrNull { it.name == settings.get(MODE) } ?: ThemeMode.System,
            palette = ThemePalette.entries.firstOrNull { it.name == settings.get(PALETTE) } ?: ThemePalette.Inn,
            contrast = ThemeContrast.entries.firstOrNull { it.name == settings.get(CONTRAST) },
            black = settings.get(BLACK) == "true",
            feedCards = settings.get(FEED_CARDS) != "false",
            feedScrubber = settings.get(FEED_SCRUBBER) != "false",
            postMedia = PostMedia.entries.firstOrNull { it.name == settings.get(POST_MEDIA) } ?: PostMedia.Carousel,
            linkPreviews = settings.get(LINK_PREVIEWS) != "false",
            pictureLinks = settings.get(PICTURE_LINKS) != "false",
            mediaOpensPost = settings.get(MEDIA_OPENS_POST) == "true",
            tickedBelow = settings.get(TICKED_BELOW) != "false",
            mediaHover = settings.get(MEDIA_HOVER) == "true",
            lightBeyondPage = settings.get(LIGHT_BEYOND_PAGE) != "false",
            pageBorders = settings.get(PAGE_BORDERS) != "false",
            staticBackdrop = settings.get(STATIC_BACKDROP) == "true",
            feedFonts = settings.get(FEED_FONTS) == "true",
        )
    }
}

/** This device's appearance, for what draws without the services at hand (the list and entry panes). */
internal val LocalAppearance = compositionLocalOf { Appearance() }

/** Pictures not placed in a post's text, when it's read: one swipeable card, an album grid, or large thumbnails. */
enum class PostMedia { Carousel, Grid, Strip }

/** The phone's Material You colors where the platform has them (Android 12+), else null. */
@Composable
internal expect fun rememberSystemColors(dark: Boolean): ColorScheme?

/** The system's wish for contrast as Material's level, 0 to 1 (Android 14+, a browser's `prefers-contrast`), else 0. */
@Composable
internal expect fun rememberSystemContrast(): Double

/** The palette every device wears ([ThemeChoice]), else this device's own from before; with its colors of one's own. */
internal fun themeSpecOf(choice: ThemeChoice, appearance: Appearance, systemContrast: Double): ThemeSpec = ThemeSpec(
    palette = choice.palette?.let { name ->
        ThemePalette.entries.firstOrNull { it.name == name } ?: ThemePalette.Inn
    } ?: appearance.palette,
    own = choice.own(),
    contrast = appearance.contrast?.level ?: systemContrast,
    black = appearance.black,
)

/** «Своя» as stored; null without a color it can read. */
internal fun ThemeChoice.own(): OwnPalette? = colorOfHex(seed)?.let { seed ->
    OwnPalette(
        seed = seed,
        character = PaletteCharacter.entries.firstOrNull { it.name == character } ?: PaletteCharacter.Calm,
        ground = PaletteGround.entries.firstOrNull { it.name == ground } ?: PaletteGround.Own,
        accent = colorOfHex(accent),
    )
}

/** [own] as stored, every field of it. */
internal fun ThemeChoice.withOwn(own: OwnPalette) =
    copy(seed = own.seed.hex(), character = own.character.name, ground = own.ground.name, accent = own.accent?.hex())

/** The palette every device wears, as it changes: read at once for the first frame, then followed. */
@Composable
internal fun rememberThemeChoice(services: AppServices): State<ThemeChoice> {
    val choices = remember(services) { services.themeChoices() }
    return choices.collectAsStateWithLifecycle(remember(services) { services.themeChoiceNow() })
}

/** [BenbowTheme] with this device's appearance: for the app and the capture sheet alike. */
@Composable
fun AppTheme(services: AppServices, content: @Composable () -> Unit) {
    val appearance by services.appearance.collectAsStateWithLifecycle()
    val choice by rememberThemeChoice(services)
    val dark = appearance.mode.isDark()
    val spec = themeSpecOf(choice, appearance, rememberSystemContrast())
    BenbowTheme(darkTheme = dark, spec = spec, systemColors = rememberSystemColors(dark)) {
        ThemeOutside(MaterialTheme.colorScheme, dark)
        CompositionLocalProvider(
            LocalAppearance provides appearance,
            LocalTickedBelow provides appearance.tickedBelow,
            // Only the web has the switch; desktop and phones keep the hover they had.
            LocalMediaHover provides (appearance.mediaHover || services.platform != "web"),
            content = content,
        )
    }
}

/**
 * This device's theme as a scheme in [dark] or light, outside the composition (ADR-057): a window's ground before the
 * first frame, the widgets, a notification's accent. [systemContrast] is what the platform says now.
 */
fun AppServices.themeScheme(dark: Boolean, systemColors: ColorScheme?, systemContrast: Double): ColorScheme =
    schemeOf(themeSpecOf(themeChoiceNow(), appearance.value, systemContrast), dark, systemColors)

/** This device's mode, outside the composition: null as the system's, else light or dark. */
fun AppServices.themeDark(): Boolean? = when (appearance.value.mode) {
    ThemeMode.System -> null
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/** The theme told to what draws outside the app's own frame (ADR-057): the browser's bar and its next loading page. */
@Composable
internal expect fun ThemeOutside(scheme: ColorScheme, dark: Boolean)
