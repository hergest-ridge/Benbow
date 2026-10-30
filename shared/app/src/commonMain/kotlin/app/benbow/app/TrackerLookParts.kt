package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.ink_names
import app.benbow.app.resources.look_more_colors
import app.benbow.app.resources.pick_color
import app.benbow.app.resources.pick_color_chroma
import app.benbow.app.resources.pick_color_done
import app.benbow.app.resources.pick_color_hint
import app.benbow.app.resources.pick_color_hue
import app.benbow.app.resources.tracker_grid_names
import app.benbow.app.resources.tracker_mark_names
import app.benbow.app.resources.trackers_ink_columns
import app.benbow.ui.theme.BenbowPalette
import app.benbow.ui.theme.BenbowTheme
import app.benbow.ui.theme.HueToken
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

// «Оформление трекера»'s own parts (owner, ADR-058): its marks' color («Цвет отметок»), how they are drawn («Стиль
// отметок»), its grid («Сетка»), and many more colors for its paper and its marks, with a wheel of one's own.

/** A tracker's parts of its look as tokens, with what the sample tiles are drawn in, and how each is changed. */
internal class SheetParts(
    val ink: String?,
    val mark: String?,
    val grid: String?,
    val sampleInk: Color,
    val onInk: (String?) -> Unit,
    val onMark: (String?) -> Unit,
    val onGrid: (String?) -> Unit,
) {
    val set: Boolean get() = ink != null || mark != null || grid != null

    /** «Сбросить оформление»: the marks back in their columns' colors, soft, with no grid. */
    fun reset() {
        if (ink != null) onInk(null)
        if (mark != null) onMark(null)
        if (grid != null) onGrid(null)
    }
}

/** The tracker's parts in the list of «Оформление», after its color. */
@Composable
internal fun SheetPartRows(parts: SheetParts, base: Color, onOpen: (LookSection) -> Unit) {
    val inks = stringArrayResource(Res.array.ink_names)
    val marks = stringArrayResource(Res.array.tracker_mark_names)
    val grids = stringArrayResource(Res.array.tracker_grid_names)
    val outline = MaterialTheme.colorScheme.outlineVariant
    val inkName = when {
        parts.ink == null -> stringResource(Res.string.trackers_ink_columns)
        HueToken.of(parts.ink) != null -> stringResource(Res.string.pick_color)
        else -> inks.getOrNull(BenbowPalette.tokens.indexOf(parts.ink)) ?: parts.ink
    }
    PartRow(LookSection.Ink.title, inkName, { onOpen(LookSection.Ink) }) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(parts.sampleInk).border(1.dp, outline, CircleShape))
    }
    val style = MarkStyle.of(parts.mark)
    PartRow(LookSection.Marks.title, marks.getOrElse(style.ordinal) { style.name }, { onOpen(LookSection.Marks) }) {
        Tile(base) { drawMarkSample(style, parts.sampleInk) }
    }
    val grid = GridStyle.of(parts.grid)
    val gridColors = gridInk(parts.sampleInk, base)
    PartRow(LookSection.Grid.title, grids.getOrElse(grid.ordinal) { grid.name }, { onOpen(LookSection.Grid) }) {
        Tile(base) { drawGridSample(grid, gridColors) }
    }
}

@Composable
private fun Tile(base: Color, draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.size(
            44.dp,
            30.dp,
        ).clip(shape).background(base).drawBehind(draw).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    )
}

/** «Стиль отметок»: every style as a tile, a short run drawn in it. */
@Composable
internal fun MarkGrid(parts: SheetParts, base: Color) {
    val names = stringArrayResource(Res.array.tracker_mark_names)
    val chosen = MarkStyle.of(parts.mark)
    Grid {
        MarkStyle.entries.forEach { style ->
            Choice(
                names.getOrElse(style.ordinal) {
                    style.name
                },
                style == chosen,
                base,
                Modifier.drawBehind { drawMarkSample(style, parts.sampleInk) },
            ) {
                parts.onMark(style.token)
            }
        }
    }
}

/** «Сетка»: every grid as a tile of a few cells. */
@Composable
internal fun GridGrid(parts: SheetParts, base: Color) {
    val names = stringArrayResource(Res.array.tracker_grid_names)
    val chosen = GridStyle.of(parts.grid)
    val colors = gridInk(parts.sampleInk, base)
    Grid {
        GridStyle.entries.forEach { style ->
            Choice(
                names.getOrElse(style.ordinal) {
                    style.name
                },
                style == chosen,
                base,
                Modifier.drawBehind { drawGridSample(style, colors) },
            ) {
                parts.onGrid(style.token)
            }
        }
    }
}

/**
 * A note's or a tracker's paper: the note colors ([black] after them, a note's), then many more hues in three strengths
 * and greys, and one of one's own.
 */
@Composable
internal fun PaperColors(selected: String?, noneLabel: String, onPick: (String?) -> Unit, black: Boolean = false) {
    val dark = BenbowTheme.colors.dark
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        SwatchPicker(selected, noneLabel, onPick, black = black)
        HueChoices(selected, HuePalette.paper, { it.fill(dark) }, HuePalette.PAPER_CHROMA, onPick)
    }
}

/** «Цвет отметок»: as the columns have them, the editor's inks, then many more hues and one of one's own. */
@Composable
internal fun InkColors(parts: SheetParts) {
    val dark = BenbowTheme.colors.dark
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        FilterChip(parts.ink == null, { parts.onInk(null) }, { Text(stringResource(Res.string.trackers_ink_columns)) }, Modifier.hand())
        InkPicker(parts.ink, Modifier.padding(top = 8.dp)) { parts.onInk(it) }
        HueChoices(parts.ink, HuePalette.ink, { it.mark(dark) }, HuePalette.INK_CHROMA, parts.onInk)
    }
}

/**
 * Many more colors (owner): rows of [rows] tokens, each drawn by [colorOf] in this theme; the rainbow circle after them
 * picks a hue and chroma of one's own ([OwnColorDialog]), ringed when the color chosen is one of those.
 */
@Composable
private fun HueChoices(
    selected: String?,
    rows: List<List<String>>,
    colorOf: (HueToken) -> Color,
    maxChroma: Int,
    onPick: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var picking by remember { mutableStateOf(false) }
    val listed = remember(rows) { rows.flatten().toSet() }
    val own = HueToken.of(selected)?.takeIf { selected !in listed }
    Text(
        stringResource(Res.string.look_more_colors),
        style = MaterialTheme.typography.titleSmall,
        color = colors.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { token ->
                    val hue = HueToken.of(token) ?: return@forEach
                    val on = token == selected
                    Box(
                        Modifier.size(HUE_DOT).clip(CircleShape).background(colorOf(hue))
                            .border(if (on) 3.dp else 1.dp, if (on) colors.primary else colors.outlineVariant, CircleShape)
                            .hand().clickable { onPick(token) }
                            .semantics { this.selected = on }
                            .testTag("color:$token"),
                    )
                }
            }
        }
        val label = stringResource(Res.string.pick_color)
        Box(
            Modifier.size(OWN_DOT).clip(CircleShape).background(Brush.sweepGradient(RAINBOW))
                .border(if (own != null) 3.dp else 0.dp, colors.primary, CircleShape)
                .hand().clickable { picking = true }
                .semantics {
                    contentDescription = label
                    this.selected = own != null
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(OWN_DOT / 2).clip(CircleShape).background(own?.let(colorOf) ?: colors.surface))
        }
    }
    if (picking) {
        OwnColorDialog(HueToken.of(selected) ?: HueToken(DEFAULT_HUE, maxChroma / 3), colorOf, maxChroma, onDismiss = { picking = false }) {
            picking = false
            onPick(it.toString())
        }
    }
}

/**
 * «Свой цвет»: a hue and a chroma on two sliders over their own colors, a preview with words on it; the tone stays the
 * theme's (HueToken), so words read on whatever is picked, in either mode.
 */
@Composable
private fun OwnColorDialog(
    initial: HueToken,
    colorOf: (HueToken) -> Color,
    maxChroma: Int,
    onDismiss: () -> Unit,
    onDone: (HueToken) -> Unit,
) {
    var hue by remember { mutableIntStateOf(initial.hue) }
    var chroma by remember { mutableIntStateOf(initial.chroma.coerceAtMost(maxChroma)) }
    val token = HueToken(hue, chroma)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.pick_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp)).background(colorOf(token))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Аа · 12 · 7:30", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(Res.string.pick_color_hue), style = MaterialTheme.typography.labelLarge)
                GradientSlider(
                    hue / 359f,
                    Brush.horizontalGradient((0..12).map { colorOf(HueToken(it * 30 % 360, maxOf(chroma, maxChroma / 2))) }),
                    colorOf(token),
                    stringResource(Res.string.pick_color_hue),
                ) { hue = (it * 359).roundToInt() }
                Text(stringResource(Res.string.pick_color_chroma), style = MaterialTheme.typography.labelLarge)
                GradientSlider(
                    chroma / maxChroma.toFloat(),
                    Brush.horizontalGradient((0..6).map { colorOf(HueToken(hue, it * maxChroma / 6)) }),
                    colorOf(token),
                    stringResource(Res.string.pick_color_chroma),
                ) { chroma = (it * maxChroma).roundToInt() }
                Text(
                    stringResource(Res.string.pick_color_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton({ onDone(token) }, Modifier.hand()) { Text(stringResource(Res.string.pick_color_done)) } },
        dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** A slider over its own colors: a tap or a drag sets [value] (0 to 1); its thumb in the color picked. */
@Composable
private fun GradientSlider(value: Float, track: Brush, thumb: Color, label: String, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(SLIDER)) {
        val travel = with(density) { (maxWidth - SLIDER).toPx() }
        val at = { x: Float, width: Int ->
            ((x - with(density) { SLIDER.toPx() } / 2) / (width - with(density) { SLIDER.toPx() })).coerceIn(0f, 1f)
        }
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(SLIDER / 2)).background(track)
                .hand()
                .pointerInput(Unit) { detectTapGestures { change(at(it.x, size.width)) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { pointer, _ -> change(at(pointer.position.x, size.width)) } }
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                    setProgress { target ->
                        change(target.coerceIn(0f, 1f))
                        true
                    }
                },
        )
        Box(
            Modifier.offset { IntOffset((value.coerceIn(0f, 1f) * travel).roundToInt(), 0) }.size(SLIDER)
                .border(3.dp, Color.White, CircleShape).padding(3.dp).clip(CircleShape).background(thumb),
        )
    }
}

/** The palette's many colors: hues every 20° in three strengths each, and three greys (a cold, a plain, a warm). */
internal object HuePalette {
    private val HUES = (0 until 360 step 20).toList()

    /** A paper's strengths: soft enough under words at the theme's page tone. */
    val paper: List<List<String>> = listOf(6, 16, 30).map { chroma -> HUES.map { "hue:$it:$chroma" } } +
        listOf(listOf("hue:0:0", "hue:220:4", "hue:50:5"))

    /** A mark's: from a quiet ink to a bright one. */
    val ink: List<List<String>> = listOf(24, 48, 76).map { chroma -> HUES.map { "hue:$it:$chroma" } } +
        listOf(listOf("hue:0:0", "hue:220:8", "hue:40:10"))

    const val PAPER_CHROMA = 48
    const val INK_CHROMA = 100
}

private val RAINBOW = listOf(
    Color(0xFFFF4D4D),
    Color(0xFFFFB84D),
    Color(0xFFF5E642),
    Color(0xFF5CD65C),
    Color(0xFF4DD2FF),
    Color(0xFF5C6CFF),
    Color(0xFFD65CFF),
    Color(0xFFFF4D4D),
)
private val HUE_DOT = 28.dp
private val OWN_DOT = 36.dp
private val SLIDER = 30.dp
private const val DEFAULT_HUE = 210
