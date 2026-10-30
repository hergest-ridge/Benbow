package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.swatch_black
import app.benbow.app.resources.swatch_names
import app.benbow.client.SWATCHES
import app.benbow.client.TagRegistry
import app.benbow.ui.theme.BenbowPalette
import app.benbow.ui.theme.BenbowTheme
import app.benbow.ui.theme.InTheDark
import app.benbow.ui.theme.SwatchColors
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** A note color ([SWATCHES]) on paper, as CSS for printed pages: the same whatever a device's theme; a black note on white. */
internal fun lightSwatch(name: String?): String? =
    name?.takeIf { it != SwatchColors.BLACK }?.let { BenbowPalette.printSwatches.fill(it, dark = false) }
        ?.let { "#" + (it.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0') }

/** The tag registry (names, categories, colors by id) for the whole app, provided once at the root. */
internal val LocalTags = compositionLocalOf { TagRegistry() }

/** A note color ([SWATCHES]) in the theme (ADR-057), for a note's page, a card or a chip under words. */
@Composable
internal fun swatch(name: String?): Color? = BenbowTheme.colors.let { it.swatches.fill(name, it.dark) }

/**
 * What is drawn on a note of [color]: a black note's words and controls in the theme's dark twin, so they read on it in a
 * light theme too; any other color as it is. Lists use it, where a note turned black may build its card anew.
 */
@Composable
internal fun NightNote(color: String?, content: @Composable () -> Unit) = if (color ==
    SwatchColors.BLACK
) {
    InTheDark(true, content)
} else {
    content()
}

/** A note color that stands out on the page: a calendar's dots and bars, the day's strip, a category's dot or name. */
@Composable
internal fun markColor(name: String?): Color? = BenbowTheme.colors.let { it.swatches.mark(name, it.dark) }

/** A tag's chip color (by its id): its own, else its category's, else a quiet neutral. */
@Composable
internal fun tagColor(tagId: String): Color = swatch(LocalTags.current.color(tagId)) ?: MaterialTheme.colorScheme.surfaceContainerHighest

/**
 * Pick one of the colors, or [noneLabel] for none (back to the default); without [noneLabel] a color is a must. [black]
 * offers a note's black after them.
 */
@Composable
internal fun SwatchPicker(
    selected: String?,
    noneLabel: String?,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
    black: Boolean = false,
) {
    val names = stringArrayResource(Res.array.swatch_names) + if (black) listOf(stringResource(Res.string.swatch_black)) else emptyList()
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (noneLabel != null) FilterChip(selected == null, { onPick(null) }, { Text(noneLabel) }, modifier = Modifier.hand())
        (if (black) SWATCHES + SwatchColors.BLACK else SWATCHES).forEachIndexed { i, name ->
            val on = name == selected
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(swatch(name) ?: Color.Transparent)
                    .border(
                        if (on) 3.dp else 1.dp,
                        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
                    .hand().clickable { onPick(name) }
                    .semantics {
                        contentDescription = names.getOrElse(i) { name }
                        this.selected = on
                    },
            )
        }
    }
}
