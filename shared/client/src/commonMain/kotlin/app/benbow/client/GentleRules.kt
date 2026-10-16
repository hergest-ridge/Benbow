package app.benbow.client

import kotlinx.serialization.Serializable

/**
 * Бережно (ADR-031): a curtain over the entry, or quiet (kept out of passive places). [Open] is only ever set by hand:
 * the entry shows as usual although a rule would veil it.
 */
enum class Veil(val value: String) {
    Curtain("curtain"),
    Quiet("quiet"),
    Open("open"),
    ;

    companion object {
        fun of(value: String?): Veil? = entries.firstOrNull { it.value == value }
    }
}

/** What an entry shows as: its own mark wins over the rules ([Veil.Open] clears them); never [Veil.Open] itself. */
fun effectiveVeil(own: Veil?, ruled: Veil?): Veil? = when (own) {
    Veil.Open -> null
    null -> ruled
    else -> own
}

/**
 * A rule: entries with a tag, with any tag of a category, or on a day within a period get [level] ("off" keeps the
 * rule without applying it). Stored in the system entry `system-gentle` as the LWW field `rule:<id>`.
 */
data class GentleRule(
    val id: String,
    val name: String,
    val kind: String,
    /** The tag's or the category's name; empty for a period. */
    val value: String = "",
    /** A period's first and last day ("YYYY-MM-DD", both included). */
    val from: String = "",
    val to: String = "",
    val level: String = CURTAIN,
) {
    /** The veil this rule gives; null while it is off. */
    val veil: Veil? get() = Veil.of(level)?.takeIf { it != Veil.Open }

    internal fun encode(): String = ClientJson.encodeToString(Stored.serializer(), Stored(name, kind, value, from, to, level))

    @Serializable
    private class Stored(
        val name: String = "",
        val kind: String = "",
        val value: String = "",
        val from: String = "",
        val to: String = "",
        val level: String = CURTAIN,
    )

    companion object {
        const val TAG = "tag"
        const val CATEGORY = "category"
        const val PERIOD = "period"
        const val OFF = "off"
        const val CURTAIN = "curtain"
        const val QUIET = "quiet"

        internal fun decode(id: String, json: String): GentleRule? = runCatching {
            ClientJson.decodeFromString(Stored.serializer(), json).let {
                GentleRule(id, it.name, it.kind, it.value, it.from, it.to, it.level)
            }
        }.getOrNull()
    }
}

/** The rules, with the tag categories they need to match; [today] decides whether a put-off entry is back. */
class GentleRules(val rules: List<GentleRule>, private val categories: Map<String, String>, val today: String = "") {
    fun matches(rule: GentleRule, day: String, tags: List<String>): Boolean = when (rule.kind) {
        GentleRule.TAG -> tags.any { it.equals(rule.value, ignoreCase = true) }
        GentleRule.CATEGORY -> tags.any { categories[tagName(it).lowercase()]?.equals(rule.value, ignoreCase = true) == true }
        GentleRule.PERIOD -> rule.from.isNotEmpty() && day >= rule.from && day <= rule.to
        else -> false
    }

    /** The strongest veil the rules give an entry (quiet over curtain), null when none applies. */
    fun ruled(day: String, tags: List<String>): Veil? {
        val levels = rules.mapNotNull { rule -> rule.veil?.takeIf { matches(rule, day, tags) } }
        return if (Veil.Quiet in levels) Veil.Quiet else levels.firstOrNull()
    }

    /** "Отложить до…": quiet until the day comes, whatever the entry's own mark; then its own mark again. */
    fun own(mark: Veil?, until: String?): Veil? = if (until != null && today < until) Veil.Quiet else mark

    /** What an entry shows as now. */
    fun veilOf(mark: Veil?, until: String?, day: String, tags: List<String>): Veil? = effectiveVeil(own(mark, until), ruled(day, tags))

    fun apply(entry: EntrySummary): EntrySummary {
        val ruled = ruled(entry.day, entry.tags)
        return entry.copy(veil = effectiveVeil(own(entry.ownVeil, entry.until), ruled), ruleVeil = ruled)
    }

    companion object {
        val NONE = GentleRules(emptyList(), emptyMap())

        /** From the fields of the rules' registry and of the tag registry (`cat:<tag key>` = category). */
        internal fun of(rules: Map<String, String?>, tags: Map<String, String?>, today: String) = GentleRules(
            rules.mapNotNull { (key, value) ->
                if (!key.startsWith(RULE_PREFIX) || value == null) null else GentleRule.decode(key.removePrefix(RULE_PREFIX), value)
            }.sortedBy { it.name.lowercase() },
            tags.mapNotNull { (key, value) ->
                if (key.startsWith(CATEGORY_PREFIX) &&
                    value != null
                ) {
                    key.removePrefix(CATEGORY_PREFIX) to value
                } else {
                    null
                }
            }
                .toMap(),
            today,
        )
    }
}

/** The Бережно rules' registry: a system entry like the tag registry, its fields `rule:<id>` (ADR-031). */
internal const val GENTLE_REGISTRY_ID = "system-gentle"
internal const val RULE_PREFIX = "rule:"
