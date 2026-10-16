package app.benbow.client

import app.benbow.core.content.TagFields
import app.benbow.core.content.TagNames
import kotlin.math.absoluteValue

/** Color names for tags and categories (Google Keep's note colors); the app maps each to a light and a dark shade. */
val SWATCHES = listOf("coral", "peach", "sand", "mint", "sage", "fog", "storm", "dusk", "blossom", "clay")

/** A tag on an entry or in a list: [id] never changes (ADR-032), [name] is what it shows as. */
data class TagRef(val id: String, val name: String)

/** A tag category: [id] never changes; the built-in ones and those named before ids have their name in lower case. */
data class TagCategory(val id: String, val name: String)

/** The category of a mood check-in's feelings (5.4); it can be renamed and recolored, not put in the trash. */
const val EMOTIONS = "эмоции"

/** The built-in categories start with their own colors; any other category gets one from its id. */
private val DEFAULT_CATEGORY_COLORS = mapOf("люди" to "storm", "места" to "mint", "проекты" to "dusk", EMOTIONS to "blossom")

// Registry fields (ADR-032, `TagFields` in shared/core) next to TagNames' `name:`, `into:` and `trash:`.
internal const val CATEGORY_PREFIX = TagFields.CATEGORY
internal const val COLOR_PREFIX = TagFields.COLOR
internal const val DESCRIPTION_PREFIX = TagFields.DESCRIPTION
internal const val ARCHIVED_PREFIX = TagFields.ARCHIVED
internal const val CATEGORY_NAME_PREFIX = TagFields.CATEGORY_NAME
internal const val CATEGORY_COLOR_PREFIX = TagFields.CATEGORY_COLOR
internal const val CATEGORY_DESCRIPTION_PREFIX = TagFields.CATEGORY_DESCRIPTION
internal const val CATEGORY_EMOJI_PREFIX = TagFields.CATEGORY_EMOJI
internal const val CATEGORY_ORDER_PREFIX = TagFields.CATEGORY_ORDER
internal const val CATEGORY_TRASH_PREFIX = TagFields.CATEGORY_TRASH

/** A category's id from what `cat:<tag id>` holds: the id, or the name it had before ids (which in lower case is its id). */
internal fun categoryId(value: String): String = TagFields.categoryId(value)

/**
 * The tag registry (the system entry `system-tags`, ADR-032): everything about tags and categories by id. How tags read
 * (names, merges, the trash) is [read], shared with the export. A tag shows its own color, else its category's, else none
 * (a neutral chip). Archived tags still show on their entries; they just stay out of suggestions.
 */
data class TagRegistry(
    val read: TagNames = TagNames(),
    /** Tag id to when it went to the trash. */
    val trashedAt: Map<String, Long> = emptyMap(),
    val descriptions: Map<String, String> = emptyMap(),
    val archived: Set<String> = emptySet(),
    /** Tag id to its own color. */
    val own: Map<String, String> = emptyMap(),
    /** Tag id to its category's id, whether that category is in the trash or not. */
    val tagCategories: Map<String, String> = emptyMap(),
    val categoryNames: Map<String, String> = emptyMap(),
    val categoryColors: Map<String, String> = emptyMap(),
    val categoryDescriptions: Map<String, String> = emptyMap(),
    val categoryEmoji: Map<String, String> = emptyMap(),
    /** The categories outside the trash, in their order ([TagFields.categoryOrder]). */
    val categoryIds: List<String> = emptyList(),
    /** Category id to when it went to the trash. */
    val categoryTrash: Map<String, Long> = emptyMap(),
) {
    /** Tag id to its own name; tags without one show the name they were put on with. */
    val names: Map<String, String> get() = read.names

    /** The tag [id] reads as (the end of its merges). */
    fun root(id: String): String = read.root(id)

    fun inTrash(id: String): Boolean = read.inTrash(id)

    fun name(tag: TagRef): String = root(tag.id).let { names[it] ?: names[tag.id] ?: tag.name }

    /** [tags] as they show: merged ones as the tag they went into, once; those in the trash left out; by name. */
    fun resolve(tags: List<TagRef>): List<TagRef> =
        tags.filterNot { inTrash(it.id) }.map { TagRef(root(it.id), name(it)) }.distinctBy { it.id }.sortedBy { it.name.lowercase() }

    /** The tag a typed name stands for (any case): one with that name of its own, not in the trash; a merged one leads on. */
    fun named(name: String): String? = names.entries.filter { it.value.equals(name, ignoreCase = true) && !inTrash(it.key) }
        .minWithOrNull(compareBy<Map.Entry<String, String>> { it.key in read.into }.thenBy { it.key })?.let { root(it.key) }

    /** The tag [id] and every tag merged into it: the keys its entries may carry. */
    fun family(id: String): Set<String> = read.into.keys.filterTo(hashSetOf(id)) { root(it) == id }

    fun description(tag: String): String? = descriptions[root(tag)]

    fun isArchived(tag: String): Boolean = root(tag) in archived

    /** The category a tag shows in (null = none, or its category is in the trash). */
    fun category(tag: String): String? = tagCategories[root(tag)]?.takeIf { it !in categoryTrash }

    /** Every category a tag is or was linked to through itself or the tag it went into, in the trash or not (for Бережно). */
    fun linkedCategories(tag: String): Set<String> = setOfNotNull(tagCategories[tag], tagCategories[root(tag)])

    fun categoryName(id: String): String = categoryNames[id] ?: id

    /** The categories outside the trash: in their own order, else the built-in ones first, then by name. */
    val categories: List<TagCategory> by lazy { categoryIds.map { TagCategory(it, categoryName(it)) } }

    /** Categories in the trash, most recent first. */
    val trashedCategories: List<TagCategory> get() =
        categoryTrash.entries.sortedByDescending { it.value }.map { TagCategory(it.key, categoryName(it.key)) }

    fun categoryColor(id: String): String =
        categoryColors[id] ?: DEFAULT_CATEGORY_COLORS[id] ?: SWATCHES[id.hashCode().absoluteValue % SWATCHES.size]

    fun ownColor(tag: String): String? = own[root(tag)]

    fun color(tag: String): String? = root(tag).let { own[it] ?: category(it)?.let(::categoryColor) }

    companion object {
        internal fun of(fields: Map<String, String?>): TagRegistry {
            fun prefixed(prefix: String) = TagFields.prefixed(fields, prefix)
            return TagRegistry(
                read = TagNames.of(fields),
                trashedAt = prefixed(TagNames.TRASH_PREFIX).mapValues { it.value.toLongOrNull() ?: 0 },
                descriptions = prefixed(DESCRIPTION_PREFIX),
                archived = prefixed(ARCHIVED_PREFIX).keys,
                own = prefixed(COLOR_PREFIX),
                tagCategories = prefixed(CATEGORY_PREFIX).mapValues { categoryId(it.value) },
                categoryNames = TagFields.categoryNames(fields),
                categoryColors = prefixed(CATEGORY_COLOR_PREFIX),
                categoryDescriptions = prefixed(CATEGORY_DESCRIPTION_PREFIX),
                categoryEmoji = prefixed(CATEGORY_EMOJI_PREFIX),
                categoryIds = TagFields.categoryOrder(fields),
                categoryTrash = prefixed(CATEGORY_TRASH_PREFIX).mapValues { it.value.toLongOrNull() ?: 0 },
            )
        }
    }
}
