package app.benbow.core.content

/**
 * Tags by id (ADR-032). An entry's LWW field `tag:<id>` holds the name the tag had when it was put on; the id never
 * changes (tags made before ids keep their old key, the name in lower case, as their id). The tag registry, the
 * system entry [REGISTRY_ID], names a tag with `name:<id>` once it has a name of its own, and that name wins.
 * The app and the server's mirror read names the same way.
 */
object TagNames {
    const val REGISTRY_ID = "system-tags"
    const val TAG_PREFIX = "tag:"
    const val NAME_PREFIX = "name:"

    /** The registry's own names, by tag id. */
    fun of(registry: Map<String, String?>): Map<String, String> = buildMap {
        for ((key, value) in registry) if (key.startsWith(NAME_PREFIX) && value != null) put(key.removePrefix(NAME_PREFIX), value)
    }

    /** An entry's tags as id to name, the registry's name else the entry's own, ordered by name. */
    fun on(fields: Map<String, String?>, names: Map<String, String>): List<Pair<String, String>> = fields.mapNotNull { (key, value) ->
        if (key.startsWith(TAG_PREFIX) && value != null) key.removePrefix(TAG_PREFIX).let { it to (names[it] ?: value) } else null
    }.sortedBy { it.second.lowercase() }
}
