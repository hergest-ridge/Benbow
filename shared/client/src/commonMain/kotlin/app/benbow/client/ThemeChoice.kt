package app.benbow.client

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// The theme's palette (ADR-057): one for every device, kept in the system entry `system-theme` as LWW fields, like
// Home's layout (ADR-034). Until the first choice it has no fields and each device keeps the palette it had; a device's
// mode, contrast and black stay its own. Not content: not exported; protocol unchanged.

/**
 * The palette chosen for every device, and the colors of one's own («Своя»). All text, so a name or a color only a
 * newer app knows is kept as it is and drawn as the default.
 */
data class ThemeChoice(
    /** A palette's name (`Inn`, `Sea`, …, `System`, `Own`); null, not chosen on any device yet. */
    val palette: String? = null,
    /** «Своя»: its color as `#RRGGBB`. */
    val seed: String? = null,
    /** «Своя»: its character's name (`Calm`, `Bright`, …). */
    val character: String? = null,
    /** «Своя»: what its pages lean to (`Own`, `Warm`, `Cool`, `Gray`). */
    val ground: String? = null,
    /** «Своя»: a second accent as `#RRGGBB`; null, the character's. */
    val accent: String? = null,
) {
    internal fun fields(): Map<String, String?> =
        mapOf(PALETTE to palette, SEED to seed, CHARACTER to character, GROUND to ground, ACCENT to accent)

    internal companion object {
        const val PALETTE = "palette"
        const val SEED = "own.seed"
        const val CHARACTER = "own.character"
        const val GROUND = "own.ground"
        const val ACCENT = "own.accent"

        fun of(fields: Map<String, String?>) = ThemeChoice(fields[PALETTE], fields[SEED], fields[CHARACTER], fields[GROUND], fields[ACCENT])
    }
}

fun JournalRepository.themeChoice(): Flow<ThemeChoice> = q.selectEntry(THEME_REGISTRY_ID).asFlow().mapToOneOrNull(context).map { registry ->
    ThemeChoice.of(registry?.let { decodeFields(it.fields) }.orEmpty())
}.distinctUntilChanged()

/** The choice as stored now, read at once: the first frame already wears it. */
fun JournalRepository.currentThemeChoice(): ThemeChoice =
    ThemeChoice.of(q.selectEntry(THEME_REGISTRY_ID).executeAsOneOrNull()?.let { decodeFields(it.fields) }.orEmpty())

/**
 * Applies [edit] to the choice as stored now, in one transaction, and writes only the fields it changed: picking a
 * palette leaves the colors of one's own as they are, and what another device changed meanwhile stays.
 */
fun JournalRepository.editThemeChoice(edit: (ThemeChoice) -> ThemeChoice) {
    db.transaction {
        val stored = currentThemeChoice()
        val before = stored.fields()
        edit(stored).fields().forEach { (name, value) -> if (before[name] != value) setRegistryField(name, value, THEME_REGISTRY_ID) }
    }
}

internal const val THEME_REGISTRY_ID = "system-theme"
