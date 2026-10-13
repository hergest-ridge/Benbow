package app.benbow.client

import kotlin.math.absoluteValue

/** Color names for tags and categories (Google Keep's note colors); the app maps each to a light and a dark shade. */
val SWATCHES = listOf("coral", "peach", "sand", "mint", "sage", "fog", "storm", "dusk", "blossom", "clay")

/** The built-in categories start with their own colors; any other category gets one from its name. */
private val DEFAULT_CATEGORY_COLORS = mapOf("люди" to "storm", "места" to "mint", "проекты" to "dusk", "эмоции" to "blossom")

// Registry fields next to `cat:<tag key>` (ADR-021): a tag's own color and a category's color.
internal const val COLOR_PREFIX = "color:"
internal const val CATEGORY_COLOR_PREFIX = "catcolor:"

/**
 * How tags look, from the tag registry: a tag's own color wins, then its category's color;
 * a tag with neither stays neutral (null).
 */
data class TagLooks(
    val own: Map<String, String> = emptyMap(),
    val categories: Map<String, String> = emptyMap(),
    val categoryColors: Map<String, String> = emptyMap(),
) {
    fun categoryColor(category: String): String = category.lowercase().let { key ->
        categoryColors[key] ?: DEFAULT_CATEGORY_COLORS[key] ?: SWATCHES[key.hashCode().absoluteValue % SWATCHES.size]
    }

    fun ownColor(tag: String): String? = own[tagName(tag).lowercase()]

    fun color(tag: String): String? = tagName(tag).lowercase().let { key -> own[key] ?: categories[key]?.let(::categoryColor) }

    companion object {
        internal fun of(fields: Map<String, String?>): TagLooks {
            fun prefixed(prefix: String) = fields.filter { it.key.startsWith(prefix) && it.value != null }
                .map { it.key.removePrefix(prefix) to it.value!! }.toMap()
            return TagLooks(prefixed(COLOR_PREFIX), prefixed(CATEGORY_PREFIX), prefixed(CATEGORY_COLOR_PREFIX))
        }
    }
}
