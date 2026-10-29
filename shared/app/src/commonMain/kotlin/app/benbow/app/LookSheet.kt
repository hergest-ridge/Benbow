package app.benbow.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.benbow.app.resources.back
import app.benbow.app.resources.background_none
import app.benbow.app.resources.look_close
import app.benbow.app.resources.look_color
import app.benbow.app.resources.look_credit_ambient
import app.benbow.app.resources.look_credit_hero
import app.benbow.app.resources.look_credit_morris
import app.benbow.app.resources.look_font
import app.benbow.app.resources.look_font_plain
import app.benbow.app.resources.look_font_sample
import app.benbow.app.resources.look_fonts_accent
import app.benbow.app.resources.look_fonts_diary
import app.benbow.app.resources.look_fonts_mono
import app.benbow.app.resources.look_fonts_own
import app.benbow.app.resources.look_fonts_sans
import app.benbow.app.resources.look_fonts_serif
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
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/*
 * «Оформление записки» (ADR-047, ADR-054): its parts as a list (color, font, paper, pattern, light, motion, scrapbook),
 * each with what is chosen now; a tap opens the part as a grid of every choice, drawn over the note's color, the chosen
 * one framed (owner: no sideways galleries). Every choice is saved at once, a field of its own, so the note behind
 * changes with it. On a phone it is a sheet with the note's preview on top; in a wide window a pane beside the open
 * note (LookPanel), which is in sight, so no preview. With a tracker's screen (ADR-051), «Фон экрана» follows.
 */

/** What «Оформление» shows and changes: a note's look, or a tracker's with the screen behind its sheet. */
internal class LookTarget(
    val id: String,
    val words: String,
    val color: String?,
    val look: Map<String, String>,
    /** The page's color when the note has none of its own (the board's cards differ from the journal's page). */
    val page: Color,
    val onColor: (String?) -> Unit,
    val onLook: (Map<String, String?>) -> Unit,
    val screen: Map<String, String>? = null,
    val screenPage: Color = page,
    val onScreen: (Map<String, String?>) -> Unit = {},
    /** «Шрифт»: a note's words take a font, a tracker's sheet doesn't. */
    val fonts: Boolean = true,
) {
    val base: Color @Composable get() = swatch(color) ?: page
}

/** On a phone: a sheet opened whole, the note's preview on top, then the parts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LookSheet(target: LookTarget, onDismiss: () -> Unit, title: StringResource = Res.string.look_title) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            LookParts(target, preview = true)
        }
    }
}

/** In a wide window: a pane beside the open note (owner), with no gestures of its own, only ✕ to close it. */
@Composable
internal fun LookPanel(
    target: LookTarget,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    title: StringResource = Res.string.look_title,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClose, Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.look_close)) }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                LookParts(target, preview = false)
            }
        }
    }
}

/** The parts of a look and its screen's, each a grid of its own once opened. */
private enum class LookSection(val title: StringResource, val layer: LookLayer? = null, val screen: Boolean = false) {
    Color(Res.string.look_color),
    Font(Res.string.look_font),
    Paper(Res.string.look_paper, LookLayer.Paper),
    Pattern(Res.string.look_pattern, LookLayer.Pattern),
    Light(Res.string.look_light, LookLayer.Light),
    Motion(Res.string.look_motion, LookLayer.Motion),
    Scrap(Res.string.look_scrap, LookLayer.Scrap),
    ScreenPattern(Res.string.look_pattern, LookLayer.Pattern, screen = true),
    ScreenLight(Res.string.look_light, LookLayer.Light, screen = true),
    ScreenMotion(Res.string.look_motion, LookLayer.Motion, screen = true),
}

private val NOTE_PARTS = listOf(LookSection.Paper, LookSection.Pattern, LookSection.Light, LookSection.Motion, LookSection.Scrap)
private val SCREEN_PARTS = listOf(LookSection.ScreenPattern, LookSection.ScreenLight, LookSection.ScreenMotion)

@Composable
private fun LookParts(target: LookTarget, preview: Boolean) {
    // The server's fonts as it lists them now (ADR-052): a font added on its page shows here the next time.
    val server = LocalServerFonts.current
    if (target.fonts && server != null) LaunchedEffect(server) { runCatching { withContext(IoDispatcher) { server.refresh() } } }
    var open by remember { mutableStateOf<LookSection?>(null) }
    AnimatedContent(
        open,
        transitionSpec = {
            val inward = targetState != null
            (slideInHorizontally { if (inward) it / 5 else -it / 5 } + fadeIn()) togetherWith fadeOut() using SizeTransform(clip = false)
        },
        label = "look section",
    ) { part ->
        Column(Modifier.fillMaxWidth()) {
            if (part == null) PartList(target, preview) { open = it } else PartScreen(target, part) { open = null }
        }
    }
}

@Composable
private fun PartList(target: LookTarget, preview: Boolean, onOpen: (LookSection) -> Unit) {
    val base = target.base
    if (preview) {
        if (target.screen == null) {
            Preview(target.id, target.words, target.look, base)
        } else {
            Box(
                Modifier.clipToBounds().background(target.screenPage)
                    .then(entryBackdrop(screenId(target.id), target.screen, target.screenPage, muted = true, moving = true)),
            ) { Preview(target.id, target.words, target.look, base) }
        }
    }
    PartRow(
        LookSection.Color.title,
        if (target.color ==
            null
        ) {
            stringResource(Res.string.background_none)
        } else {
            null
        },
        { onOpen(LookSection.Color) },
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(base).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
    }
    if (target.fonts) {
        val token = target.look[LookFields.FONT]
        PartRow(LookSection.Font.title, fontName(token), { onOpen(LookSection.Font) }) {
            Text(
                stringResource(Res.string.look_font_sample),
                style = MaterialTheme.typography.titleMedium.inFont(noteFont(token)),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
    NOTE_PARTS.forEach { part -> LayerRow(target, part, onOpen) }
    if (target.screen != null) {
        Text(
            stringResource(Res.string.look_screen),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
        )
        SCREEN_PARTS.forEach { part -> LayerRow(target, part, onOpen) }
    }
    val screen = target.screen
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(
            {
                target.onLook(LookFields.LAYERS.associateWith { null })
                if (!screen.isNullOrEmpty()) target.onScreen(SCREEN_LAYERS.associate { it.field to null })
            },
            enabled = target.look.keys.any { it in LookFields.LAYERS } || !screen.isNullOrEmpty(),
            modifier = Modifier.hand(),
        ) { Text(stringResource(Res.string.look_reset)) }
    }
}

/** A part in the list: its name, what is chosen now in words and in a small picture, and a way in. */
@Composable
private fun PartRow(title: StringResource, chosen: String?, onOpen: () -> Unit, picture: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().hand().clickable(onClick = onOpen).padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f).width(12.dp))
        chosen?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 10.dp).widthIn(max = 180.dp),
            )
        }
        // The pictures in one column, so the chevrons stand in a line.
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) { picture() }
        Icon(Icons.ChevronRight, null, Modifier.padding(start = 6.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LayerRow(target: LookTarget, part: LookSection, onOpen: (LookSection) -> Unit) {
    val layer = part.layer!!
    val (id, look, base) = if (part.screen) {
        Triple(
            screenId(target.id),
            target.screen.orEmpty(),
            target.screenPage,
        )
    } else {
        Triple(target.id, target.look, target.base)
    }
    val chosen = look[layer.field]
    val labels = stringArrayResource(namesOf(layer))
    val kind = if (layer == LookLayer.Scrap) NoteLooks.scrapOf(chosen).first else chosen
    val name = kind?.let { labels.getOrNull(NoteLooks.choices(layer).indexOf(it)) ?: it } ?: stringResource(Res.string.look_none)
    PartRow(part.title, name, { onOpen(part) }) {
        val shape = RoundedCornerShape(6.dp)
        Box(
            Modifier.size(44.dp, 30.dp).clip(shape).background(base)
                .then(if (chosen != null) entryBackdrop(id, mapOf(layer.field to chosen), base, muted = false) else Modifier)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        )
    }
}

/** An opened part: back to the list, then every choice in a grid that wraps (it scrolls down, never sideways). */
@Composable
private fun PartScreen(target: LookTarget, part: LookSection, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.hand()) { Icon(Icons.Back, stringResource(Res.string.back)) }
        Text(
            stringResource(part.title) + if (part.screen) " · " + stringResource(Res.string.look_screen) else "",
            style = MaterialTheme.typography.titleMedium,
        )
    }
    when (part) {
        LookSection.Color -> SwatchPicker(
            target.color,
            stringResource(Res.string.background_none),
            target.onColor,
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        LookSection.Font -> FontGrid(target)

        else -> LayerGrid(target, part)
    }
}

@Composable
private fun LayerGrid(target: LookTarget, part: LookSection) {
    val layer = part.layer!!
    val id = if (part.screen) screenId(target.id) else target.id
    val look = if (part.screen) target.screen.orEmpty() else target.look
    val base = if (part.screen) target.screenPage else target.base
    val onLook = if (part.screen) target.onScreen else target.onLook
    val labels = stringArrayResource(namesOf(layer))
    val chosen = look[layer.field]
    // A scrapbook stays chosen when shuffled ("tape.7" is still "tape").
    val chosenKind = if (layer == LookLayer.Scrap) NoteLooks.scrapOf(chosen).first else chosen
    Grid {
        Choice(stringResource(Res.string.look_none), chosen == null, base, Modifier) { onLook(mapOf(layer.field to null)) }
        NoteLooks.choices(layer).forEachIndexed { i, token ->
            val drawn = if (layer == LookLayer.Scrap && token == chosenKind) chosen!! else token
            Choice(
                labels.getOrElse(i) { token },
                token == chosenKind,
                base,
                entryBackdrop(id, mapOf(layer.field to drawn), base, muted = false),
            ) { onLook(mapOf(layer.field to token)) }
        }
    }
    when {
        layer == LookLayer.Paper && NoteLooks.isPhoto(chosen) -> Credit(Res.string.look_credit_ambient)
        layer == LookLayer.Pattern && NoteLooks.isHero(chosen) -> Credit(Res.string.look_credit_hero)
        layer == LookLayer.Pattern && NoteLooks.isMorris(chosen) -> Credit(Res.string.look_credit_morris)
        layer == LookLayer.Motion -> Credit(Res.string.look_motion_hint)
    }
    if (layer == LookLayer.Scrap) {
        TextButton(
            { chosen?.let { onLook(mapOf(LookLayer.Scrap.field to NoteLooks.shuffled(it))) } },
            enabled = chosenKind != null,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).hand(),
        ) { Text(stringResource(Res.string.look_shuffle)) }
    }
}

/** «Шрифт» (L3-L5): «Обычный», then every font by its group, the diary's hands first, each sample drawn in it. */
@Composable
private fun FontGrid(target: LookTarget) {
    val base = target.base
    val chosen = target.look[LookFields.FONT]
    val catalog = LocalServerFonts.current?.catalog?.collectAsStateWithLifecycle()?.value.orEmpty()
    val groups = remember(catalog) { NoteFonts.choices(catalog).groupBy { it.group } }
    val pick = { token: String? -> target.onLook(mapOf(LookFields.FONT to token)) }
    Grid { FontChoice(stringResource(Res.string.look_font_plain), null, chosen == null, base) { pick(null) } }
    groups.forEach { (group, fonts) ->
        Text(
            stringResource(groupTitle(group)),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 2.dp),
        )
        Grid { fonts.forEach { font -> FontChoice(font.name, noteFont(font.token), font.token == chosen, base) { pick(font.token) } } }
    }
}

@Composable
private fun Grid(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/** The name of the note's font for the list: «Обычный» for none, the token itself for one this device doesn't know. */
@Composable
private fun fontName(token: String?): String {
    if (token == null) return stringResource(Res.string.look_font_plain)
    val catalog = LocalServerFonts.current?.catalog?.collectAsStateWithLifecycle()?.value.orEmpty()
    return catalog.firstOrNull { it.token == token }?.name ?: NoteFonts.name(token)
}

private fun groupTitle(group: String): StringResource = when (group) {
    "diary" -> Res.string.look_fonts_diary
    "own" -> Res.string.look_fonts_own
    "serif" -> Res.string.look_fonts_serif
    "sans" -> Res.string.look_fonts_sans
    "mono" -> Res.string.look_fonts_mono
    else -> Res.string.look_fonts_accent
}

private fun namesOf(layer: LookLayer): StringArrayResource = when (layer) {
    LookLayer.Paper -> Res.array.look_papers
    LookLayer.Pattern -> Res.array.look_patterns
    LookLayer.Light -> Res.array.look_lights
    LookLayer.Motion -> Res.array.look_motions
    LookLayer.Scrap -> Res.array.look_scraps
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
