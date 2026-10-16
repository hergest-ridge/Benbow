package app.benbow.core.content

/**
 * The tag registry's fields (ADR-032) next to [TagNames]' `name:`, `into:` and `trash:`, each followed by a tag's or a
 * category's id. The app reads and writes them; the export reads them for `tags.json` and `tags.md` ([TagBook]).
 */
object TagFields {
    /** A tag's category: its id (written before ids: its name, which in lower case is the id). */
    const val CATEGORY = "cat:"
    const val COLOR = "color:"
    const val DESCRIPTION = "desc:"
    const val ARCHIVED = "archived:"
    const val CATEGORY_NAME = "catname:"
    const val CATEGORY_COLOR = "catcolor:"
    const val CATEGORY_DESCRIPTION = "catdesc:"
    const val CATEGORY_EMOJI = "catemoji:"
    const val CATEGORY_ORDER = "catsort:"
    const val CATEGORY_TRASH = "cattrash:"

    /** The built-in categories by id, in their order; their ids are their names in lower case. */
    val BUILT_IN: Map<String, String> = linkedMapOf("люди" to "Люди", "места" to "Места", "проекты" to "Проекты", "эмоции" to "Эмоции")

    /** A category's id from what `cat:<tag id>` holds. */
    fun categoryId(value: String): String = value.trim().lowercase()

    /** The fields with [prefix], by the id after it; removed ones (null) left out. */
    fun prefixed(registry: Map<String, String?>, prefix: String): Map<String, String> = buildMap {
        for ((key, value) in registry) if (key.startsWith(prefix) && value != null) put(key.removePrefix(prefix), value)
    }

    /** Category names: those written into tags' links before ids (capitals kept), the built-in ones, then their own. */
    fun categoryNames(registry: Map<String, String?>): Map<String, String> {
        val written = prefixed(registry, CATEGORY).values.groupBy(::categoryId)
            .mapValues { (id, values) -> values.map { it.trim() }.firstOrNull { it != id } ?: id }
        return written + BUILT_IN + prefixed(registry, CATEGORY_NAME)
    }

    /** Category ids outside the trash in their order: their own place, else the built-in order, then by name. */
    fun categoryOrder(registry: Map<String, String?>): List<String> {
        val names = categoryNames(registry)
        val order = prefixed(registry, CATEGORY_ORDER).mapNotNull { (id, value) -> value.toDoubleOrNull()?.let { id to it } }.toMap()
        val trashed = prefixed(registry, CATEGORY_TRASH).keys
        val builtIn = BUILT_IN.keys.toList()
        val links = prefixed(registry, CATEGORY).values.map(::categoryId)
        return (builtIn + links + names.keys).distinct().filter { it !in trashed }.sortedWith(
            compareBy<String>({
                order[it] ?: builtIn.indexOf(it).takeIf { i -> i >= 0 }?.toDouble() ?: Double.MAX_VALUE
            }, { names[it].orEmpty().lowercase() }),
        )
    }
}
