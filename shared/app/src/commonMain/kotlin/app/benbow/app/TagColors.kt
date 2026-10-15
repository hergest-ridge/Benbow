package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.swatch_names
import app.benbow.client.SWATCHES
import app.benbow.client.TagLooks
import org.jetbrains.compose.resources.stringArrayResource

/** Google Keep's note colors by name ([SWATCHES]): a light and a dark shade, so tags read well in both themes. */
private val SHADES: Map<String, Pair<Long, Long>> = mapOf(
    "coral" to (0xFFFAAFA8 to 0xFF77172E),
    "peach" to (0xFFF39F76 to 0xFF692B17),
    "sand" to (0xFFFFF8B8 to 0xFF7C4A03),
    "mint" to (0xFFE2F6D3 to 0xFF264D3B),
    "sage" to (0xFFB4DDD3 to 0xFF0C625D),
    "fog" to (0xFFD4E4ED to 0xFF256377),
    "storm" to (0xFFAECCDC to 0xFF284255),
    "dusk" to (0xFFD3BFDB to 0xFF472E5B),
    "blossom" to (0xFFF6E2DD to 0xFF6C394F),
    "clay" to (0xFFE9E3D4 to 0xFF4B443A),
)

/** The light shade as CSS, for printed pages. */
internal fun lightSwatch(name: String?): String? = SHADES[name]?.first?.let { "#" + (it and 0xFFFFFF).toString(16).padStart(6, '0') }

/** The tag registry's looks for the whole app, provided once at the root. */
internal val LocalTagLooks = compositionLocalOf { TagLooks() }

@Composable
internal fun swatch(name: String?): Color? {
    val (light, dark) = SHADES[name] ?: return null
    return Color(if (MaterialTheme.colorScheme.background.luminance() < 0.5f) dark else light)
}

/** A calendar's mark (grid dots and bars, the day's strip): the shade that stands out against the theme. */
@Composable
internal fun markColor(name: String?): Color? {
    val (light, dark) = SHADES[name] ?: return null
    return Color(if (MaterialTheme.colorScheme.background.luminance() < 0.5f) light else dark)
}

/** A tag's chip color: its own, else its category's, else a quiet neutral. */
@Composable
internal fun tagColor(tag: String): Color = swatch(LocalTagLooks.current.color(tag)) ?: MaterialTheme.colorScheme.surfaceContainerHighest

/** Pick one of the colors, or [noneLabel] for none (back to the default); without [noneLabel] a color is a must. */
@Composable
internal fun SwatchPicker(selected: String?, noneLabel: String?, onPick: (String?) -> Unit, modifier: Modifier = Modifier) {
    val names = stringArrayResource(Res.array.swatch_names)
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (noneLabel != null) FilterChip(selected == null, { onPick(null) }, { Text(noneLabel) })
        SWATCHES.forEachIndexed { i, name ->
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
                    .clickable { onPick(name) }
                    .semantics {
                        contentDescription = names.getOrElse(i) { name }
                        this.selected = on
                    },
            )
        }
    }
}
