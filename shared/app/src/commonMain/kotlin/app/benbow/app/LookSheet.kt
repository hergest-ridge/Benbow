package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.background_none
import app.benbow.app.resources.look_color
import app.benbow.app.resources.look_credit_ambient
import app.benbow.app.resources.look_credit_hero
import app.benbow.app.resources.look_credit_morris
import app.benbow.app.resources.look_font
import app.benbow.app.resources.look_font_plain
import app.benbow.app.resources.look_font_sample
import app.benbow.app.resources.look_light
import app.benbow.app.resources.look_lights
import app.benbow.app.resources.look_motion
import app.benbow.app.resources.look_motion_hint
import app.benbow.app.resources.look_motions
import app.benbow.app.resources.look_none
import app.benbow.app.resources.look_paper
import app.benbow.app.resources.look_papers
import app.benbow.app.resources.look_pattern
import app.benbow.app.resources.look_patterns
import app.benbow.app.resources.look_reset
import app.benbow.app.resources.look_scrap
import app.benbow.app.resources.look_scraps
import app.benbow.app.resources.look_screen
import app.benbow.app.resources.look_shuffle
import app.benbow.app.resources.look_title
import app.benbow.client.IoDispatcher
import app.benbow.client.LookFields
import app.benbow.ui.editor.NoteFont
import app.benbow.ui.editor.inFont
import app.benbow.ui.mouseSwipe
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * «Оформление записки» (ADR-047, replacing «Фон»): the note as it will look on top, then its color and each layer of
 * its look as a row of small previews drawn over that color. Every choice is saved at once, a field of its own, so
 * the page behind changes with it. The sets that ask for credit get it under their row while one of theirs is on.
 * With a [screen] (a tracker's, ADR-051), «Фон экрана» follows: the screen behind the sheet, its pattern, light and
 * motion over [screenPage], and the preview sits on it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LookSheet(
    id: String,
    words: String,
    color: String?,
    look: Map<String, String>,
    /** The page's color when the note has none of its own (the board's cards differ from the journal's page). */
    page: Color,
    onColor: (String?) -> Unit,
    onLook: (Map<String, String?>) -> Unit,
    onDismiss: () -> Unit,
    /** «Оформление трекера» for a tracker's sheet (ADR-050), which takes the same look. */
    title: StringResource = Res.string.look_title,
    screen: Map<String, String>? = null,
    screenPage: Color = page,
    onScreen: (Map<String, String?>) -> Unit = {},
    /** «Шрифт»: a note's words take a font, a tracker's sheet doesn't. */
    fonts: Boolean = true,
) {
    val base = swatch(color) ?: page
    // Opened whole: the note on top and every row under it (half open, only its color would show).
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (screen == null) {
                Preview(id, words, look, base)
            } else {
                Box(
                    Modifier.clipToBounds().background(
                        screenPage,
                    ).then(entryBackdrop(screenId(id), screen, screenPage, muted = true, moving = true)),
                ) {
                    Preview(id, words, look, base)
                }
            }
            Part(Res.string.look_color) {
                SwatchPicker(color, stringResource(Res.string.background_none), onColor, Modifier.padding(horizontal = 20.dp))
            }
            if (fonts) FontRow(look[LookFields.FONT], base, onLook)
            Layer(LookLayer.Paper, Res.string.look_paper, Res.array.look_papers, id, look, base, onLook)
            if (NoteLooks.isPhoto(look[LookLayer.Paper.field])) Credit(Res.string.look_credit_ambient)
            Layer(LookLayer.Pattern, Res.string.look_pattern, Res.array.look_patterns, id, look, base, onLook)
            when {
                NoteLooks.isHero(look[LookLayer.Pattern.field]) -> Credit(Res.string.look_credit_hero)
                NoteLooks.isMorris(look[LookLayer.Pattern.field]) -> Credit(Res.string.look_credit_morris)
            }
            Layer(LookLayer.Light, Res.string.look_light, Res.array.look_lights, id, look, base, onLook)
            Layer(LookLayer.Motion, Res.string.look_motion, Res.array.look_motions, id, look, base, onLook)
            Credit(Res.string.look_motion_hint)
            Layer(LookLayer.Scrap, Res.string.look_scrap, Res.array.look_scraps, id, look, base, onLook)
            if (screen != null) ScreenLayers(id, screen, screenPage, onScreen)
            val scrap = look[LookLayer.Scrap.field]
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    { scrap?.let { onLook(mapOf(LookLayer.Scrap.field to NoteLooks.shuffled(it))) } },
                    enabled = NoteLooks.scrapOf(scrap).first != null,
                    modifier = Modifier.hand(),
                ) { Text(stringResource(Res.string.look_shuffle)) }
                TextButton(
                    {
                        onLook(LookFields.LAYERS.associateWith { null })
                        if (!screen.isNullOrEmpty()) onScreen(SCREEN_LAYERS.associate { it.field to null })
                    },
                    enabled = look.keys.any { it in LookFields.LAYERS } || !screen.isNullOrEmpty(),
                    modifier = Modifier.hand(),
                ) { Text(stringResource(Res.string.look_reset)) }
            }
        }
    }
}

/** «Фон экрана» (ADR-051): the screen behind a tracker's sheet, a note's pattern, light and motion drawn over [page]. */
@Composable
private fun ScreenLayers(id: String, screen: Map<String, String>, page: Color, onScreen: (Map<String, String?>) -> Unit) {
    Text(
        stringResource(Res.string.look_screen),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp),
    )
    Layer(LookLayer.Pattern, Res.string.look_pattern, Res.array.look_patterns, screenId(id), screen, page, onScreen)
    when {
        NoteLooks.isHero(screen[LookLayer.Pattern.field]) -> Credit(Res.string.look_credit_hero)
        NoteLooks.isMorris(screen[LookLayer.Pattern.field]) -> Credit(Res.string.look_credit_morris)
    }
    Layer(LookLayer.Light, Res.string.look_light, Res.array.look_lights, screenId(id), screen, page, onScreen)
    Layer(LookLayer.Motion, Res.string.look_motion, Res.array.look_motions, screenId(id), screen, page, onScreen)
}

/** What lays out a screen's look: not its sheet's, so the two patterns don't line up. */
internal fun screenId(id: String) = "$id/screen"

/** The note itself, its first words over its look, the way the open note shows it. */
@Composable
private fun Preview(id: String, words: String, look: Map<String, String>, base: Color) {
    val font = noteFont(look[LookFields.FONT])
    val (title, rest) = remember(words) {
        words.trim().lines().filter { it.isNotBlank() }.let {
            it.firstOrNull().orEmpty() to
                it.drop(1).joinToString(" ")
        }
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().height(150.dp).clip(shape).background(base)
            .then(entryBackdrop(id, look, base, muted = false, moving = true))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.inFont(font),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            rest,
            style = MaterialTheme.typography.bodyMedium.inFont(font),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Part(title: StringResource, content: @Composable () -> Unit) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
    )
    content()
}

/** «Шрифт» (L3-L5): «Обычный», then every font, the diary's hands first, its sample drawn in it over the note's color. */
@Composable
private fun FontRow(chosen: String?, base: Color, onLook: (Map<String, String?>) -> Unit) = Part(Res.string.look_font) {
    // The server's fonts as it lists them now (ADR-052): a font added on its page shows here the next time.
    val server = LocalServerFonts.current
    if (server != null) LaunchedEffect(server) { runCatching { withContext(IoDispatcher) { server.refresh() } } }
    val catalog = server?.catalog?.collectAsStateWithLifecycle()?.value.orEmpty()
    val choices = remember(catalog) { NoteFonts.choices(catalog) }
    val swipeRow = rememberLazyListState()
    LazyRow(
        state = swipeRow,
        modifier = Modifier.mouseSwipe(swipeRow),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            FontChoice(stringResource(Res.string.look_font_plain), null, chosen == null, base) { onLook(mapOf(LookFields.FONT to null)) }
        }
        items(choices, key = { it.token }) { font ->
            FontChoice(font.name, noteFont(font.token), font.token == chosen, base) { onLook(mapOf(LookFields.FONT to font.token)) }
        }
    }
}

@Composable
private fun FontChoice(name: String, font: NoteFont?, on: Boolean, base: Color, onPick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(TILE_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(TILE_WIDTH, 60.dp).clip(shape).background(base)
                .border(
                    if (on) 3.dp else 1.dp,
                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape,
                )
                .hand().clickable(onClick = onPick)
                .semantics {
                    contentDescription = name
                    selected = on
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(Res.string.look_font_sample),
                style = MaterialTheme.typography.headlineSmall.inFont(font),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** A layer's row: «Нет», then each of its choices drawn alone over the note's color, the chosen one framed. */
@Composable
private fun Layer(
    layer: LookLayer,
    title: StringResource,
    names: StringArrayResource,
    id: String,
    look: Map<String, String>,
    base: Color,
    onLook: (Map<String, String?>) -> Unit,
) = Part(title) {
    val labels = stringArrayResource(names)
    val chosen = look[layer.field]
    // A scrapbook stays chosen when shuffled ("tape.7" is still "tape").
    val chosenKind = if (layer == LookLayer.Scrap) NoteLooks.scrapOf(chosen).first else chosen
    val choices = NoteLooks.choices(layer)
    val swipeRow = rememberLazyListState()
    LazyRow(
        state = swipeRow,
        modifier = Modifier.mouseSwipe(swipeRow),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Choice(stringResource(Res.string.look_none), chosen == null, base, Modifier) { onLook(mapOf(layer.field to null)) } }
        itemsIndexed(choices) { i, token ->
            val drawn = if (layer == LookLayer.Scrap && token == chosenKind) chosen!! else token
            Choice(
                labels.getOrElse(i) { token },
                token == chosenKind,
                base,
                entryBackdrop(id, mapOf(layer.field to drawn), base, muted = false),
            ) { onLook(mapOf(layer.field to token)) }
        }
    }
}

@Composable
private fun Choice(name: String, on: Boolean, base: Color, backdrop: Modifier, onPick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(TILE_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(TILE_WIDTH, 60.dp).clip(shape).background(base).then(backdrop)
                .border(
                    if (on) 3.dp else 1.dp,
                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape,
                )
                .hand().clickable(onClick = onPick)
                .semantics {
                    contentDescription = name
                    selected = on
                },
        )
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Credit(text: StringResource) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
    )
}

private val TILE_WIDTH = 76.dp
