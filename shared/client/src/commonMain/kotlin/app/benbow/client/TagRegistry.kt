package app.benbow.client

import app.benbow.core.content.TagNames
import kotlin.math.absoluteValue

/** Color names for tags and categories (Google Keep's note colors); the app maps each to a light and a dark shade. */
val SWATCHES = listOf("coral", "peach", "sand", "mint", "sage", "fog", "storm", "dusk", "blossom", "clay")

/** A tag on an entry or in a list: [id] never changes (ADR-032), [name] is what it shows as. */
data class TagRef(val id: String, val name: String)

/** A tag category: [id] never changes; the built-in ones and those named before ids have their name in lower case. */
data class TagCategory(val id: String, val name: String)

/** The built-in categories, in their order. */
internal val BUILT_IN_CATEGORIES =
    listOf(TagCategory("люди", "Люди"), TagCategory("места", "Места"), TagCategory("проекты", "Проекты"), TagCategory(EMOTIONS, "Эмоции"))

/** The category of a mood check-in's feelings (5.4). */
const val EMOTIONS = "эмоции"

/** The built-in categories start with their own colors; any other category gets one from its id. */
private val DEFAULT_CATEGORY_COLORS = mapOf("люди" to "storm", "места" to "mint", "проекты" to "dusk", EMOTIONS to "blossom")

// Registry fields (ADR-021, ADR-032) next to `name:<tag id>`, each keyed by a tag's or a category's id.
internal const val CATEGORY_PREFIX = "cat:" // a tag's category: its id (before ids, its name)
internal const val COLOR_PREFIX = "color:"
internal const val CATEGORY_NAME_PREFIX = "catname:"
internal const val CATEGORY_COLOR_PREFIX = "catcolor:"

/** A category's id from what `cat:<tag id>` holds: the id, or the name it had before ids (which in lower case is its id). */
internal fun categoryId(value: String): String = value.trim().lowercase()

/**
 * The tag registry (the system entry `system-tags`, ADR-032): names, categories and colors by id. A tag shows the
 * registry's name, else the one on the entry; its own color, else its category's, else none (a neutral chip).
 */
data class TagRegistry(
    /** Tag id to its own name; tags without one show the name they were put on with. */
    val names: Map<String, String> = emptyMap(),
    /** Tag id to its own color. */
    val own: Map<String, String> = emptyMap(),
    /** Tag id to its category's id. */
    val tagCategories: Map<String, String> = emptyMap(),
    val categoryNames: Map<String, String> = emptyMap(),
    val categoryColors: Map<String, String> = emptyMap(),
) {
    fun name(tag: TagRef): String = names[tag.id] ?: tag.name

    /** [tags] with the names they show as, by name. */
    fun resolve(tags: List<TagRef>): List<TagRef> = tags.map { it.copy(name = name(it)) }.sortedBy { it.name.lowercase() }

    /** The tag with this name of its own (any case); tags named only by their entries aren't here. */
    fun named(name: String): String? = names.entries.filter { it.value.equals(name, ignoreCase = true) }.minOfOrNull { it.key }

    /** The tag's category id, null = none. */
    fun category(tag: String): String? = tagCategories[tag]

    fun categoryName(id: String): String = categoryNames[id] ?: id

    /** The built-in categories first, then the others by name. */
    val categories: List<TagCategory> by lazy {
        val builtIn = BUILT_IN_CATEGORIES.map { it.id }
        val others = (tagCategories.values + categoryNames.keys).distinct().filter {
            it !in builtIn
        }.sortedBy { categoryName(it).lowercase() }
        (builtIn + others).map { TagCategory(it, categoryName(it)) }
    }

    fun categoryColor(id: String): String =
        categoryColors[id] ?: DEFAULT_CATEGORY_COLORS[id] ?: SWATCHES[id.hashCode().absoluteValue % SWATCHES.size]

    fun ownColor(tag: String): String? = own[tag]

    fun color(tag: String): String? = own[tag] ?: category(tag)?.let(::categoryColor)

    companion object {
        internal fun of(fields: Map<String, String?>): TagRegistry {
            fun prefixed(prefix: String) = fields.mapNotNull { (key, value) ->
                if (key.startsWith(prefix) && value != null) key.removePrefix(prefix) to value else null
            }.toMap()
            val links = prefixed(CATEGORY_PREFIX)
            // A category named before ids lives only in its tags' links: the name as written there (capitals kept).
            val written = links.values.groupBy(::categoryId).mapValues { (id, values) ->
                values.map { it.trim() }.firstOrNull { it != id }
                    ?: id
            }
            return TagRegistry(
                names = TagNames.of(fields),
                own = prefixed(COLOR_PREFIX),
                tagCategories = links.mapValues { categoryId(it.value) },
                categoryNames = written + BUILT_IN_CATEGORIES.associate { it.id to it.name } + prefixed(CATEGORY_NAME_PREFIX),
                categoryColors = prefixed(CATEGORY_COLOR_PREFIX),
            )
        }
    }
}
