package app.benbow.app

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.own_accent
import app.benbow.app.resources.own_accent_hint
import app.benbow.app.resources.own_character
import app.benbow.app.resources.own_characters
import app.benbow.app.resources.own_chroma
import app.benbow.app.resources.own_color
import app.benbow.app.resources.own_done
import app.benbow.app.resources.own_ground
import app.benbow.app.resources.own_grounds
import app.benbow.app.resources.own_hex
import app.benbow.app.resources.own_hue
import app.benbow.app.resources.own_palette
import app.benbow.app.resources.own_palette_edit
import app.benbow.app.resources.own_preview_button
import app.benbow.app.resources.own_preview_dark
import app.benbow.app.resources.own_preview_light
import app.benbow.app.resources.own_preview_quiet
import app.benbow.app.resources.own_preview_text
import app.benbow.app.resources.own_preview_title
import app.benbow.app.resources.palette
import app.benbow.app.resources.palette_names
import app.benbow.app.resources.theme_black
import app.benbow.app.resources.theme_black_hint
import app.benbow.app.resources.theme_contrast
import app.benbow.app.resources.theme_contrasts
import app.benbow.app.resources.theme_mode
import app.benbow.app.resources.theme_modes
import app.benbow.client.SWATCHES
import app.benbow.ui.theme.BenbowColors
import app.benbow.ui.theme.OwnPalette
import app.benbow.ui.theme.PaletteCharacter
import app.benbow.ui.theme.PaletteGround
import app.benbow.ui.theme.ThemeContrast
import app.benbow.ui.theme.ThemeMode
import app.benbow.ui.theme.ThemePalette
import app.benbow.ui.theme.ThemeSpec
import app.benbow.ui.theme.colorOfHex
import app.benbow.ui.theme.colorsOf
import app.benbow.ui.theme.hctChroma
import app.benbow.ui.theme.hctColor
import app.benbow.ui.theme.hctHue
import app.benbow.ui.theme.hex
import app.benbow.ui.theme.isDark
import app.benbow.ui.theme.schemeOf
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * «Оформление»'s theme (ADR-057): the mode, the palette every device wears (presets, the system's, «Своя»), and this
 * device's contrast and black.
 */
@Composable
internal fun ThemeSection(services: AppServices, current: Appearance) {
    val choice by rememberThemeChoice(services)
    val dark = current.mode.isDark()
    val system = rememberSystemColors(dark)
    val spec = themeSpecOf(choice, current, rememberSystemContrast())
    val modes = stringArrayResource(Res.array.theme_modes)
    val names = stringArrayResource(Res.array.palette_names)
    val contrasts = stringArrayResource(Res.array.theme_contrasts)
    var editing by remember { mutableStateOf(false) }

    SettingsTitle(stringResource(Res.string.theme_mode))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ThemeMode.entries.forEachIndexed { i, mode ->
            FilterChip(current.mode == mode, {
                services.setAppearance(current.copy(mode = mode))
            }, { Text(modes.getOrElse(i) { mode.name }) }, modifier = Modifier.hand())
        }
    }
    SettingsTitle(stringResource(Res.string.palette))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemePalette.entries.forEachIndexed { i, palette ->
            if (palette == ThemePalette.System && system == null) return@forEachIndexed
            // Each card in this device's mode at standard contrast, so the palettes compare as themselves.
            val sample = remember(palette, dark, spec.own, system) { schemeOf(ThemeSpec(palette, spec.own), dark, system) }
            val chosen = spec.palette == palette
            val name = names.getOrElse(i) { palette.name }
            val label = if (palette == ThemePalette.Own && chosen) "$name · ${stringResource(Res.string.own_palette_edit)}" else name
            PaletteCard(label, sample, chosen) {
                if (palette == ThemePalette.Own && (chosen || spec.own == null)) editing = true else services.choosePalette(palette)
            }
        }
    }
    SettingsTitle(stringResource(Res.string.theme_contrast))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf(null) + ThemeContrast.entries).forEachIndexed { i, level ->
            FilterChip(current.contrast == level, {
                services.setAppearance(current.copy(contrast = level))
            }, { Text(contrasts.getOrElse(i) { level?.name.orEmpty() }) }, modifier = Modifier.hand())
        }
    }
    SettingSwitch(
        stringResource(Res.string.theme_black),
        stringResource(Res.string.theme_black_hint),
        current.black,
    ) { services.setAppearance(current.copy(black = it)) }

    if (editing) {
        OwnPaletteSheet(
            start = spec.own ?: OwnPalette(seed = schemeOf(spec, dark = false, system).primary),
            contrast = spec.contrast,
            onDone = { own ->
                services.choosePalette(ThemePalette.Own, own)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

@Composable
internal fun SettingsTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** A switch with its name and a line of explanation, as «Оформление»'s rows are. */
@Composable
internal fun SettingSwitch(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, onChange, modifier = Modifier.hand())
    }
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
        modifier = Modifier.width(150.dp).hand().semantics { this.selected = selected },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(scheme.primary, scheme.secondary, scheme.primaryContainer, scheme.tertiaryContainer).forEach {
                    Box(Modifier.size(22.dp).background(it, CircleShape))
                }
            }
            Box(Modifier.fillMaxWidth().height(10.dp).background(scheme.background, RoundedCornerShape(5.dp)))
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Seeds to start from: eighteen hues around the wheel at a middle tone. */
private val SEEDS = (0 until 18).map { hctColor(it * 20.0, 48.0) }

/**
 * «Своя» (ADR-057): a color (a seed, its hue and colorfulness, or HEX), a character, what the pages lean to and maybe a
 * second accent, with the theme it makes shown in both modes as it changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnPaletteSheet(start: OwnPalette, contrast: Double, onDone: (OwnPalette) -> Unit, onDismiss: () -> Unit) {
    var own by remember { mutableStateOf(start) }
    val characters = stringArrayResource(Res.array.own_characters)
    val grounds = stringArrayResource(Res.array.own_grounds)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(Res.string.own_palette),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            val spec = ThemeSpec(ThemePalette.Own, own, contrast)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemePreview(stringResource(Res.string.own_preview_light), spec, dark = false, Modifier.weight(1f))
                ThemePreview(stringResource(Res.string.own_preview_dark), spec, dark = true, Modifier.weight(1f))
            }
            SettingsTitle(stringResource(Res.string.own_color))
            ColorPicker(own.seed) { own = own.copy(seed = it) }
            SettingsTitle(stringResource(Res.string.own_character))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PaletteCharacter.entries.forEachIndexed { i, character ->
                    FilterChip(own.character == character, { own = own.copy(character = character) }, {
                        Text(characters.getOrElse(i) { character.name })
                    }, modifier = Modifier.hand())
                }
            }
            SettingsTitle(stringResource(Res.string.own_ground))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PaletteGround.entries.forEachIndexed { i, ground ->
                    FilterChip(own.ground == ground, { own = own.copy(ground = ground) }, {
                        Text(grounds.getOrElse(i) { ground.name })
                    }, modifier = Modifier.hand())
                }
            }
            SettingSwitch(stringResource(Res.string.own_accent), stringResource(Res.string.own_accent_hint), own.accent != null) { on ->
                own = own.copy(accent = if (on) hctColor(own.seed.hctHue() + 150.0, 36.0) else null)
            }
            own.accent?.let { accent -> ColorPicker(accent) { own = own.copy(accent = it) } }
            Spacer(Modifier.height(16.dp))
            Button({ onDone(own) }, Modifier.align(Alignment.End).hand()) { Text(stringResource(Res.string.own_done)) }
        }
    }
}

/** A color by a seed, its hue and colorfulness, or HEX. */
@Composable
private fun ColorPicker(color: Color, onPick: (Color) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SEEDS.forEach { seed ->
            val on = seed.hex() == color.hex()
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(seed)
                    .border(
                        if (on) 3.dp else 1.dp,
                        if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
                    .hand().clickable { onPick(seed) }
                    .semantics {
                        contentDescription = seed.hex()
                        selected = on
                    },
            )
        }
    }
    val hue = color.hctHue()
    val chroma = color.hctChroma()
    Text(
        stringResource(Res.string.own_hue),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp),
    )
    Slider(hue.toFloat(), { onPick(hctColor(it.toDouble(), chroma.coerceAtLeast(MIN_CHROMA))) }, Modifier.hand(), valueRange = 0f..359f)
    Text(stringResource(Res.string.own_chroma), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
    Slider(chroma.toFloat(), { onPick(hctColor(hue, it.toDouble())) }, Modifier.hand(), valueRange = 0f..MAX_CHROMA)
    var typed by remember(color) { mutableStateOf(color.hex()) }
    OutlinedTextField(
        typed,
        { text ->
            typed = text
            colorOfHex(text)?.let(onPick)
        },
        label = { Text(stringResource(Res.string.own_hex)) },
        singleLine = true,
        modifier = Modifier.width(160.dp),
    )
}

/** The theme [spec] makes in one mode, small: a page, a card of words, the note colors and a button. */
@Composable
private fun ThemePreview(label: String, spec: ThemeSpec, dark: Boolean, modifier: Modifier = Modifier) {
    val scheme = remember(spec, dark) { schemeOf(spec, dark) }
    val colors: BenbowColors = remember(spec, dark) { colorsOf(spec, dark) }
    Column(
        modifier.clip(
            RoundedCornerShape(16.dp),
        ).background(scheme.background).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerLow).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(Res.string.own_preview_title), style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
            Text(stringResource(Res.string.own_preview_text), style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
            Text(stringResource(Res.string.own_preview_quiet), style = MaterialTheme.typography.labelSmall, color = colors.textQuiet)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SWATCHES.take(5).forEach {
                Box(Modifier.size(14.dp).background(colors.swatches.fill(it, dark) ?: Color.Transparent, CircleShape))
            }
        }
        Box(Modifier.clip(RoundedCornerShape(50)).background(scheme.primary).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(stringResource(Res.string.own_preview_button), style = MaterialTheme.typography.labelMedium, color = scheme.onPrimary)
        }
    }
}

private const val MIN_CHROMA = 16.0
private const val MAX_CHROMA = 100f
