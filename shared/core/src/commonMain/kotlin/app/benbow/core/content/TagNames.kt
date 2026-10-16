package app.benbow.core.content

/**
 * How tags read (ADR-032). An entry's LWW field `tag:<id>` holds the name the tag had when it was put on; the id never
 * changes (tags made before ids keep their old key, the name in lower case, as their id). The tag registry, the system
 * entry [REGISTRY_ID], names a tag with `name:<id>` once it has a name of its own, merges it into another with
 * `into:<id>` (it then reads as that one; taking the field away splits them again) and puts it in the trash with
 * `trash:<id>` (it then doesn't show at all). Entries never change for any of it. The app and the server's mirror read
 * tags the same way.
 */
data class TagNames(
    /** Tag id to its own name. */
    val names: Map<String, String> = emptyMap(),
    /** Tag id to the tag it was merged into. */
    val into: Map<String, String> = emptyMap(),
    /** Tags in the trash. */
    val trashed: Set<String> = emptySet(),
) {
    /**
     * The tag [id] reads as: the end of its merges. Merges made on two devices at once may run in a circle; every
     * device then takes the circle's smallest id, so they all agree.
     */
    fun root(id: String): String {
        val seen = LinkedHashSet<String>()
        var current = id
        while (true) {
            if (!seen.add(current)) return seen.dropWhile { it != current }.min()
            current = into[current] ?: return current
        }
    }

    /** Whether the tag [id] reads as one in the trash. */
    fun inTrash(id: String): Boolean = root(id) in trashed

    /** An entry's tags as they read: (id, name) of each, merged ones once, those in the trash left out, by name. */
    fun on(fields: Map<String, String?>): List<Pair<String, String>> = fields.mapNotNull { (key, value) ->
        if (!key.startsWith(TAG_PREFIX) || value == null) return@mapNotNull null
        val id = key.removePrefix(TAG_PREFIX)
        val root = root(id)
        if (root in trashed) null else root to (names[root] ?: names[id] ?: value)
    }.distinctBy { it.first }.sortedBy { it.second.lowercase() }

    /** What entries with the tag [id] show for it: the tag it reads as, that one's own name, and whether it's in the trash. */
    private fun look(id: String) = root(id).let { Triple(it, names[it], it in trashed) }

    private val ids: Set<String> get() = names.keys + into.keys + into.values + trashed

    companion object {
        const val REGISTRY_ID = "system-tags"
        const val TAG_PREFIX = "tag:"
        const val NAME_PREFIX = "name:"
        const val INTO_PREFIX = "into:"
        const val TRASH_PREFIX = "trash:"

        fun of(registry: Map<String, String?>): TagNames {
            fun prefixed(prefix: String) = buildMap {
                for ((key, value) in registry) if (key.startsWith(prefix) && value != null) put(key.removePrefix(prefix), value)
            }
            return TagNames(prefixed(NAME_PREFIX), prefixed(INTO_PREFIX), prefixed(TRASH_PREFIX).keys)
        }

        /** Whether [field] of the registry changes how tags read. */
        fun shapesTags(field: String): Boolean =
            field.startsWith(NAME_PREFIX) || field.startsWith(INTO_PREFIX) || field.startsWith(TRASH_PREFIX)

        /** Tags that read differently in [new] than in [old]: entries carrying them show something else now. */
        fun changed(old: TagNames, new: TagNames): Set<String> = (old.ids + new.ids).filterTo(HashSet()) { old.look(it) != new.look(it) }
    }
}
