package app.benbow.client

/**
 * The Snowball Russian stemmer (snowballstem.org/algorithms/russian): strips endings so the forms of a word share one
 * stem ("море", "моря", "морем" → "мор"). Words without Cyrillic letters come back unchanged (lowercased).
 * Search indexes and queries go through the same function, so they always agree.
 */
internal object RussianStemmer {
    private const val VOWELS = "аеиоуыэюя"

    private val PERFECTIVE_GERUND_1 = listOf("в", "вши", "вшись")
    private val PERFECTIVE_GERUND_2 = listOf("ив", "ивши", "ившись", "ыв", "ывши", "ывшись")
    private val ADJECTIVE = listOf(
        "ее", "ие", "ые", "ое", "ими", "ыми", "ей", "ий", "ый", "ой", "ем", "им", "ым", "ом",
        "его", "ого", "ему", "ому", "их", "ых", "ую", "юю", "ая", "яя", "ою", "ею",
    )
    private val PARTICIPLE_1 = listOf("ем", "нн", "вш", "ющ", "щ")
    private val PARTICIPLE_2 = listOf("ивш", "ывш", "ующ")
    private val REFLEXIVE = listOf("ся", "сь")
    private val VERB_1 = listOf("ла", "на", "ете", "йте", "ли", "й", "л", "ем", "н", "ло", "но", "ет", "ют", "ны", "ть", "ешь", "нно")
    private val VERB_2 = listOf(
        "ила", "ыла", "ена", "ейте", "уйте", "ите", "или", "ыли", "ей", "уй", "ил", "ыл", "им", "ым", "ен",
        "ило", "ыло", "ено", "ят", "ует", "уют", "ит", "ыт", "ены", "ить", "ыть", "ишь", "ую", "ю",
    )
    private val NOUN = listOf(
        "а", "ев", "ов", "ие", "ье", "е", "иями", "ями", "ами", "еи", "ии", "и", "ией", "ей", "ой", "ий", "й",
        "иям", "ям", "ием", "ем", "ам", "ом", "о", "у", "ах", "иях", "ях", "ы", "ь", "ию", "ью", "ю", "ия", "ья", "я",
    )
    private val DERIVATIONAL = listOf("ост", "ость")
    private val SUPERLATIVE = listOf("ейш", "ейше")

    fun stem(input: String): String {
        val word = input.lowercase().replace('ё', 'е')
        if (word.none { it in 'а'..'я' }) return word
        val rv = word.indexOfFirst { it in VOWELS }.takeIf { it >= 0 }?.plus(1) ?: return word
        val r2 = region(word, region(word, 0))
        val s = StringBuilder(word)

        if (!removeGroup(s, rv, PERFECTIVE_GERUND_1, PERFECTIVE_GERUND_2)) {
            remove(s, rv, REFLEXIVE)
            if (remove(s, rv, ADJECTIVE)) {
                removeGroup(s, rv, PARTICIPLE_1, PARTICIPLE_2)
            } else if (!removeGroup(s, rv, VERB_1, VERB_2)) {
                remove(s, rv, NOUN)
            }
        }
        remove(s, rv, listOf("и"))
        remove(s, r2, DERIVATIONAL)
        if (!undouble(s, rv)) {
            if (remove(s, rv, SUPERLATIVE)) undouble(s, rv) else remove(s, rv, listOf("ь"))
        }
        return s.toString()
    }

    /** Where R1 (or R2, from [start]) begins: after the first non-vowel that follows a vowel. */
    private fun region(word: String, start: Int): Int {
        var i = start
        while (i < word.length && word[i] !in VOWELS) i++
        while (i < word.length && word[i] in VOWELS) i++
        return (i + 1).coerceAtMost(word.length)
    }

    /** The longest of [endings] that lies inside the region from [from]; Snowball doesn't fall back to a shorter one. */
    private fun longest(s: CharSequence, from: Int, endings: List<String>): String? =
        endings.filter { s.endsWith(it) && s.length - it.length >= from }.maxByOrNull { it.length }

    private fun remove(s: StringBuilder, from: Int, endings: List<String>): Boolean {
        val ending = longest(s, from, endings) ?: return false
        s.setLength(s.length - ending.length)
        return true
    }

    /** Group 1 endings count only after "а" or "я" (which stay); group 2 endings always do. */
    private fun removeGroup(s: StringBuilder, from: Int, group1: List<String>, group2: List<String>): Boolean {
        val ending = longest(s, from, group1 + group2) ?: return false
        if (ending in group1 && ending !in group2) {
            val before = s.length - ending.length - 1
            if (before < from || s[before] !in "ая") return false
        }
        s.setLength(s.length - ending.length)
        return true
    }

    private fun undouble(s: StringBuilder, from: Int): Boolean {
        if (s.length - 2 < from || !s.endsWith("нн")) return false
        s.setLength(s.length - 1)
        return true
    }
}
