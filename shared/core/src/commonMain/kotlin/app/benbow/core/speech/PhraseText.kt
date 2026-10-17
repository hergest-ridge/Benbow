package app.benbow.core.speech

/**
 * A dictated phrase as it goes in after [before], the text up to the cursor (7.10): with a space when one is needed,
 * and with its first letter in lower case when [before] stops mid-sentence. The model starts every phrase as a
 * sentence of its own, so a pause after "Когда мы приехали на дачу" would give "Оказалось" (measured on the bench).
 * A word in capitals ("США") keeps them; an opening quote or bracket counts as what stands before it.
 */
fun fitPhrase(before: String, phrase: String): String {
    val text = phrase.trim()
    if (text.isEmpty()) return ""
    val last = before.lastOrNull { it != ' ' && it != '\t' && it !in OPENINGS }
    val midSentence = last != null && last != '\n' && last !in SENTENCE_ENDS
    val firstWord = text.takeWhile { it.isLetter() }
    val acronym = firstWord.length > 1 && firstWord.all { it.isUpperCase() }
    val fitted = if (midSentence && text[0].isUpperCase() && !acronym) text[0].lowercase() + text.substring(1) else text
    val space = before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in OPENINGS
    return if (space) " $fitted" else fitted
}

private const val SENTENCE_ENDS = ".!?…"
private const val OPENINGS = "([«„\"'"
