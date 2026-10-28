package app.benbow.client

/**
 * The look of a note (ADR-047): one LWW field per layer, `look_<layer>`, each a token the app draws (paper, pattern,
 * light, motion, scrap and the font of its words; a picture comes later), so two devices changing different layers
 * merge. Fields of layers this app doesn't know yet are read and kept like any other, and exports carry them in the
 * front matter.
 */
object LookFields {
    const val PREFIX = "look_"

    /** The layers this app sets. */
    val LAYERS = listOf("paper", "pattern", "light", "motion", "scrap", FONT)

    /** The font of the note's words and headings: a note's alone, never a tracker's. */
    const val FONT = "font"

    /** The look's tokens by layer, unknown layers included (a newer app's): the drawing skips what it doesn't know. */
    fun of(fields: Map<String, String?>): Map<String, String> = fields.mapNotNull { (key, value) ->
        if (key.startsWith(PREFIX) && value != null) key.removePrefix(PREFIX) to value else null
    }.toMap()

    /** A token is a short name: letters, digits, spaces and `.:;-_` (a light is "315 orange 2;45 blue 1"). */
    fun isToken(value: String): Boolean = value.length in 1..MAX_TOKEN && value.all { it.isLetterOrDigit() || it in " .:;-_" }

    private const val MAX_TOKEN = 120
}

/**
 * Sets layers of the entry's look at once ([LookFields.LAYERS] only; null takes a layer off, and so does anything that
 * isn't a token). One transaction: the page and every list change together.
 */
fun JournalRepository.setLook(id: String, layers: Map<String, String?>) = db.transaction {
    layers.forEach { (layer, token) ->
        if (layer in LookFields.LAYERS) setField(id, LookFields.PREFIX + layer, token?.takeIf(LookFields::isToken))
    }
}
