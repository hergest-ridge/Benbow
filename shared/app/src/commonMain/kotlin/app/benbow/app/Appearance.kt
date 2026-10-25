package app.benbow.app

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.client.Settings
import app.benbow.ui.theme.BenbowTheme
import app.benbow.ui.theme.ThemeMode
import app.benbow.ui.theme.ThemePalette
import app.benbow.ui.theme.isDark

/** How the app looks on this device: light or dark, and the palette. Kept in local settings, not synced. */
data class Appearance(
    val mode: ThemeMode = ThemeMode.System,
    val palette: ThemePalette = ThemePalette.Inn,
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
) {
    internal fun save(settings: Settings) {
        settings.put(MODE, mode.name)
        settings.put(PALETTE, palette.name)
        settings.put(FEED_CARDS, feedCards.toString())
        settings.put(FEED_SCRUBBER, feedScrubber.toString())
        settings.put(POST_MEDIA, postMedia.name)
        settings.put(LINK_PREVIEWS, linkPreviews.toString())
        settings.put(PICTURE_LINKS, pictureLinks.toString())
        settings.put(MEDIA_OPENS_POST, mediaOpensPost.toString())
        settings.put(TICKED_BELOW, tickedBelow.toString())
    }

    companion object {
        private const val MODE = "theme.mode"
        private const val PALETTE = "theme.palette"
        private const val FEED_CARDS = "feed.cards"
        private const val FEED_SCRUBBER = "feed.scrubber"
        private const val POST_MEDIA = "post.media"
        private const val LINK_PREVIEWS = "links.previews"
        private const val PICTURE_LINKS = "links.pictureLinks"
        private const val MEDIA_OPENS_POST = "feed.mediaOpensPost"
        private const val TICKED_BELOW = "board.tickedBelow"

        internal fun load(settings: Settings) = Appearance(
            mode = ThemeMode.entries.firstOrNull { it.name == settings.get(MODE) } ?: ThemeMode.System,
            palette = ThemePalette.entries.firstOrNull { it.name == settings.get(PALETTE) } ?: ThemePalette.Inn,
            feedCards = settings.get(FEED_CARDS) != "false",
            feedScrubber = settings.get(FEED_SCRUBBER) != "false",
            postMedia = PostMedia.entries.firstOrNull { it.name == settings.get(POST_MEDIA) } ?: PostMedia.Carousel,
            linkPreviews = settings.get(LINK_PREVIEWS) != "false",
            pictureLinks = settings.get(PICTURE_LINKS) != "false",
            mediaOpensPost = settings.get(MEDIA_OPENS_POST) == "true",
            tickedBelow = settings.get(TICKED_BELOW) != "false",
        )
    }
}

/** Pictures not placed in a post's text, when it's read: one swipeable card, an album grid, or large thumbnails. */
enum class PostMedia { Carousel, Grid, Strip }

/** The phone's Material You colors where the platform has them (Android 12+), else null. */
@Composable
internal expect fun rememberSystemColors(dark: Boolean): ColorScheme?

/** [BenbowTheme] with this device's appearance: for the app and the capture sheet alike. */
@Composable
fun AppTheme(services: AppServices, content: @Composable () -> Unit) {
    val appearance by services.appearance.collectAsStateWithLifecycle()
    val dark = appearance.mode.isDark()
    BenbowTheme(darkTheme = dark, palette = appearance.palette, systemColors = rememberSystemColors(dark)) {
        CompositionLocalProvider(LocalTickedBelow provides appearance.tickedBelow, content = content)
    }
}
