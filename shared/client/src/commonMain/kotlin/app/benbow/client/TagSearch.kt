package app.benbow.client

import kotlin.math.abs

/** A tag as the tag picker shows and searches it (7.9 T3). */
data class TagChoice(
    val id: String,
    val name: String,
    val uses: Int = 0,
    val categoryId: String? = null,
    val category: String? = null,
    val description: String? = null,
    /** The day of the newest entry with it, as lists show them ("YYYY-MM-DD"). */
    val lastDay: String? = null,
    val archived: Boolean = false,
) {
    companion object {
        /** Every tag outside the trash with what the picker needs from the registry and recent entries. */
        fun of(tags: List<TagCount>, registry: TagRegistry, usage: TagUsage): List<TagChoice> = tags.map {
            TagChoice(
                id = it.id,
                name = it.name,
                uses = it.uses,
                categoryId = it.category,
                category = it.category?.let(registry::categoryName),
                description = registry.description(it.id),
                lastDay = usage.lastDay[it.id],
                archived = it.archived,
            )
        }
    }
}

/** Where the typed text was found. */
enum class TagMatch { NAME, CATEGORY, DESCRIPTION }

/** A tag found for the typed text; [range] is the part of its name that matched, to show in bold. */
data class TagHit(val tag: TagChoice, val score: Int, val where: TagMatch = TagMatch.NAME, val range: IntRange? = null)

/**
 * The tag picker's search: forgiving, instant, on the device. A tag's name is matched as a whole, by its beginning,
 * by the beginning of any word in it, by every typed word starting some word, anywhere inside, by Russian word forms
 * ("моря" finds «Море», not «Морковь»), and with one typo in a word of four letters or more; then the beginning of a word of its
 * category or its description. Text typed in the wrong keyboard layout ("vjht" for «море») counts a little less, an
 * archived tag a little less still. Ties go to the tag used more, then more recently.
 */
object TagSearch {
    fun find(query: String, tags: List<TagChoice>): List<TagHit> {
        val typed = fold(query).trim().replace(Regex("""\s+"""), " ")
        if (typed.isEmpty()) return emptyList()
        val swapped = fold(swapLayout(typed)).takeIf { it != typed }
        return tags.mapNotNull { tag ->
            val direct = hit(tag, typed)
            val other = swapped?.let { hit(tag, it) }?.let { it.copy(score = it.score - LAYOUT_PENALTY) }
            listOfNotNull(direct, other).maxByOrNull {
                it.score
            }?.let { if (tag.archived) it.copy(score = it.score - ARCHIVED_PENALTY) else it }
        }.sortedWith(
            compareByDescending<TagHit> { it.score }.thenByDescending { it.tag.uses }.thenByDescending { it.tag.lastDay.orEmpty() }
                .thenBy { it.tag.name.lowercase() },
        )
    }

    private fun hit(tag: TagChoice, typed: String): TagHit? {
        nameMatch(fold(tag.name), typed)?.let { (score, range) -> return TagHit(tag, score, TagMatch.NAME, range) }
        if (tag.category?.let { startsAWord(fold(it), typed) } == true) return TagHit(tag, CATEGORY_SCORE, TagMatch.CATEGORY)
        if (tag.description?.let { startsAWord(fold(it), typed) } == true) return TagHit(tag, DESCRIPTION_SCORE, TagMatch.DESCRIPTION)
        return null
    }

    /** How well [typed] matches a [name] (both folded), and which part of the name it covers. */
    private fun nameMatch(name: String, typed: String): Pair<Int, IntRange?>? {
        if (name == typed) return 100 to name.indices
        if (name.startsWith(typed)) return 90 to typed.indices
        val words = words(name)
        words.firstOrNull { it.second.startsWith(typed) }?.let { (at, _) -> return 80 to (at until at + typed.length) }
        val parts = typed.split(' ')
        if (parts.size > 1 && parts.all { part -> words.any { it.second.startsWith(part) } }) return 70 to null
        name.indexOf(typed).takeIf { it >= 0 }?.let { return 60 to (it until it + typed.length) }
        val stems = parts.map(RussianStemmer::stem)
        if (stems.all { stem -> stem.length >= MIN_STEM && words.any { RussianStemmer.stem(it.second) == stem } }) {
            return 50 to null
        }
        if (parts.size == 1 && typed.length >= MIN_TYPO) {
            // One slip in a whole word, or (from a few more letters, as short beginnings are alike) in a longer word typed so far.
            val typing = typed.length >= MIN_TYPO_TYPING
            if (words.any { (_, word) ->
                    withinOne(typed, word) ||
                        (typing && word.length > typed.length && withinOne(typed, word.take(typed.length)))
                }
            ) {
                return 40 to null
            }
        }
        return null
    }

    private fun startsAWord(text: String, typed: String): Boolean = words(text).any { it.second.startsWith(typed) }

    /** The words of [text] with where each starts. */
    private fun words(text: String): List<Pair<Int, String>> = WORD.findAll(text).map { it.range.first to it.value }.toList()

    /** Lower case, «ё» as «е»: the same length as the text, so a range in it is a range in the name. */
    internal fun fold(text: String): String = text.lowercase().replace('ё', 'е')

    /** Whether [a] and [b] differ by at most one letter: changed, added, dropped, or two neighbours swapped. */
    internal fun withinOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < minOf(a.length, b.length) && a[i] == b[i]) i++
        return when {
            a.length > b.length -> a.substring(i + 1) == b.substring(i)
            a.length < b.length -> a.substring(i) == b.substring(i + 1)
            a.substring(i + 1) == b.substring(i + 1) -> true
            else -> i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2)
        }
    }

    /** The same keys in the other layout (ЙЦУКЕН and QWERTY): "vjht" is «море», «Иутищц» is "benbow". */
    internal fun swapLayout(text: String): String = text.map { c -> LATIN_TO_CYRILLIC[c] ?: CYRILLIC_TO_LATIN[c] ?: c }.joinToString("")

    private const val LATIN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private const val CYRILLIC = "йцукенгшщзхъфывапролджэячсмитьбюё"
    private val LATIN_TO_CYRILLIC = LATIN.zip(CYRILLIC).toMap()
    private val CYRILLIC_TO_LATIN = CYRILLIC.zip(LATIN).toMap()
    private val WORD = Regex("""[\p{L}\p{N}]+""")
    private const val CATEGORY_SCORE = 30
    private const val DESCRIPTION_SCORE = 25
    private const val LAYOUT_PENALTY = 5
    private const val ARCHIVED_PENALTY = 3
    private const val MIN_STEM = 3
    private const val MIN_TYPO = 4
    private const val MIN_TYPO_TYPING = 5
}

/**
 * How tags were used lately, for the picker before anything is typed: the newest day of each, the tags of the newest
 * entries first, and what goes with the tags already chosen. Built from [entries] as lists show them (their tags as
 * they read); the caller leaves out what Бережно keeps out of the way, so a tag never comes up on its own from there.
 */
class TagUsage(entries: List<EntrySummary>) {
    private val newestFirst = entries.sortedWith(compareByDescending<EntrySummary> { it.day }.thenByDescending { it.createdAt })

    val lastDay: Map<String, String> = buildMap {
        for (entry in newestFirst) {
            for (tag in entry.tags) {
                if (tag.id !in
                    this
                ) {
                    put(tag.id, entry.day)
                }
            }
        }
    }

    /** Tag ids by their newest entry. */
    val recent: List<String> = lastDay.keys.toList()

    /** Tags most often on the same entries as [chosen], the most frequent first (then the most recent). */
    fun together(chosen: Set<String>): List<String> {
        if (chosen.isEmpty()) return emptyList()
        val counts = HashMap<String, Int>()
        for (entry in newestFirst) {
            val ids = entry.tags.map { it.id }
            if (ids.none { it in chosen }) continue
            for (id in ids) if (id !in chosen) counts[id] = (counts[id] ?: 0) + 1
        }
        return counts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> {
                it.value
            }.thenByDescending { lastDay[it.key].orEmpty() },
        )
            .map { it.key }
    }
}
