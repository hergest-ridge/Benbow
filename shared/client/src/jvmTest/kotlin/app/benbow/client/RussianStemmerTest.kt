package app.benbow.client

import kotlin.test.Test
import kotlin.test.assertEquals

class RussianStemmerTest {
    private fun sameStem(vararg forms: String) {
        val stems = forms.map(RussianStemmer::stem).toSet()
        assertEquals(1, stems.size, "${forms.toList()} -> $stems")
    }

    @Test
    fun formsOfAWordShareAStem() {
        assertEquals("мор", RussianStemmer.stem("море"))
        sameStem("море", "моря", "морем", "морю", "морях")
        sameStem("прогулка", "прогулки", "прогулкой", "прогулку", "прогулках")
        sameStem("вечер", "вечера", "вечером", "вечеру")
        sameStem("красивый", "красивая", "красивые", "красивого", "красивыми")
        sameStem("гулять", "гуляли", "гуляла", "гуляю")
        sameStem("радость", "радости", "радостью")
        sameStem("ёлка", "елки", "ЁЛКОЙ")
    }

    @Test
    fun differentWordsStayApart() {
        assertEquals(false, RussianStemmer.stem("море") == RussianStemmer.stem("морковь"))
        assertEquals(false, RussianStemmer.stem("мир") == RussianStemmer.stem("мера"))
    }

    @Test
    fun otherScriptsAndShortWordsAreLeftAlone() {
        assertEquals("benbow", RussianStemmer.stem("Benbow"))
        assertEquals("2026", RussianStemmer.stem("2026"))
        assertEquals("я", RussianStemmer.stem("я"))
    }
}
