package app.benbow.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.appearance
import app.benbow.app.resources.feed_cards
import app.benbow.app.resources.feed_cards_hint
import app.benbow.app.resources.feed_scrubber
import app.benbow.app.resources.feed_scrubber_hint
import app.benbow.app.resources.feed_section
import app.benbow.app.resources.link_previews
import app.benbow.app.resources.link_previews_hint
import app.benbow.app.resources.palette
import app.benbow.app.resources.palette_names
import app.benbow.app.resources.picture_links
import app.benbow.app.resources.picture_links_hint
import app.benbow.app.resources.post_media
import app.benbow.app.resources.post_media_names
import app.benbow.app.resources.post_section
import app.benbow.app.resources.tab_board
import app.benbow.app.resources.theme_mode
import app.benbow.app.resources.theme_modes
import app.benbow.app.resources.ticked_below
import app.benbow.app.resources.ticked_below_hint
import app.benbow.ui.theme.ThemeMode
import app.benbow.ui.theme.ThemePalette
import app.benbow.ui.theme.isDark
import app.benbow.ui.theme.schemeOf
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** "Ещё → Оформление": light, dark or as the system; and the palette, each shown by its own colors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceScreen(services: AppServices, nav: Navigator) {
    val current by services.appearance.collectAsStateWithLifecycle()
    val modes = stringArrayResource(Res.array.theme_modes)
    val names = stringArrayResource(Res.array.palette_names)
    val dark = current.mode.isDark()
    val system = rememberSystemColors(dark)
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.appearance)) })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Title(stringResource(Res.string.theme_mode))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    FilterChip(current.mode == mode, {
                        services.setAppearance(current.copy(mode = mode))
                    }, { Text(modes.getOrElse(i) { mode.name }) }, modifier = Modifier.hand())
                }
            }
            Title(stringResource(Res.string.feed_section))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(Res.string.feed_cards), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(Res.string.feed_cards_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(current.feedCards, { services.setAppearance(current.copy(feedCards = it)) }, modifier = Modifier.hand())
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(Res.string.feed_scrubber), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(Res.string.feed_scrubber_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(current.feedScrubber, { services.setAppearance(current.copy(feedScrubber = it)) }, modifier = Modifier.hand())
            }
            Title(stringResource(Res.string.post_section))
            Text(
                stringResource(Res.string.post_media),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
            )
            val layouts = stringArrayResource(Res.array.post_media_names)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PostMedia.entries.forEachIndexed { i, layout ->
                    FilterChip(
                        current.postMedia == layout,
                        { services.setAppearance(current.copy(postMedia = layout)) },
                        { Text(layouts.getOrElse(i) { layout.name }) },
                        modifier = Modifier.hand(),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(Res.string.link_previews), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(Res.string.link_previews_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(current.linkPreviews, { services.setAppearance(current.copy(linkPreviews = it)) }, modifier = Modifier.hand())
            }
            // Owner's words: on, a picture link brings the picture alone (ADR-045).
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(Res.string.picture_links), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(Res.string.picture_links_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(!current.pictureLinks, { services.setAppearance(current.copy(pictureLinks = !it)) }, modifier = Modifier.hand())
            }
            Title(stringResource(Res.string.tab_board))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(Res.string.ticked_below), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(Res.string.ticked_below_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(current.tickedBelow, { services.setAppearance(current.copy(tickedBelow = it)) }, modifier = Modifier.hand())
            }
            Title(stringResource(Res.string.palette))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemePalette.entries.forEachIndexed { i, palette ->
                    val scheme = if (palette == ThemePalette.System) system else remember(palette, dark) { schemeOf(palette, dark) }
                    if (scheme != null) {
                        PaletteCard(names.getOrElse(i) { palette.name }, scheme, current.palette == palette) {
                            services.setAppearance(current.copy(palette = palette))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** A palette's name over a small sample of it: its surface, accent and two containers. */
@Composable
private fun PaletteCard(name: String, scheme: ColorScheme, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = scheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            if (selected) 3.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.width(150.dp).hand(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(scheme.primary, scheme.secondary, scheme.primaryContainer, scheme.tertiaryContainer).forEach {
                    Box(Modifier.size(22.dp).background(it, CircleShape))
                }
            }
            Box(
                Modifier.fillMaxWidth().size(
                    height = 10.dp,
                    width = 0.dp,
                ).background(scheme.surfaceContainerHighest, RoundedCornerShape(5.dp)),
            )
            Text(name, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
        }
    }
}
