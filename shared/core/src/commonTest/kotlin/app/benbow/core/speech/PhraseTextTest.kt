package app.benbow.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals

class PhraseTextTest {
    @Test
    fun aPhraseAfterASentenceStaysASentence() {
        assertEquals("Потом позвонила сестра.", fitPhrase("", " Потом позвонила сестра. "))
        assertEquals(" Потом позвонила сестра.", fitPhrase("Шёл дождь.", "Потом позвонила сестра."))
        assertEquals("Потом.", fitPhrase("Шёл дождь! ", "Потом."))
        assertEquals("Потом.", fitPhrase("Заголовок\n", "Потом."))
    }

    @Test
    fun aPhraseAfterAPauseMidSentenceGoesOnInLowerCase() {
        assertEquals(" оказалось, что ключи остались дома.", fitPhrase("Когда мы приехали на дачу", "Оказалось, что ключи остались дома."))
        assertEquals("принёс ключ.", fitPhrase("Он пришёл, ", "Принёс ключ."))
        assertEquals(" купить молока.", fitPhrase("Список:", "Купить молока."))
        assertEquals(" я пошёл.", fitPhrase("и", "Я пошёл."))
    }

    @Test
    fun capitalsQuotesAndBlanks() {
        assertEquals(" США далеко.", fitPhrase("Думаю, что", "США далеко."))
        assertEquals("Тихо.", fitPhrase("«", "Тихо."))
        assertEquals("тихо.", fitPhrase("Он сказал: «", "Тихо."))
        assertEquals("", fitPhrase("Текст", "  "))
    }
}
