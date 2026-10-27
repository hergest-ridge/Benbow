package app.benbow.app

import app.benbow.core.tracker.ChoiceOption
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The trackers' constructor (ADR-050, B5): new columns, type changes, typed numbers and hours, what a draft writes. */
class TrackerEditsTest {
    private var ids = 0
    private val newId = { "o${ids++}" }

    private fun column(vararg fields: Pair<String, String?>) = TrackerColumn(TrackerRecord("c", mapOf(*fields)))

    @Test
    fun aNewColumnIsReadyToUseAndATypeChangeWritesOnlyTheType() {
        assertEquals(mapOf("name" to "Чтение", "color" to "blue", "type" to "check"), newColumn(ColumnType.Check, "Чтение", "blue", newId))
        val choice = newColumn(ColumnType.Choice, "День", "gray", newId)
        assertEquals(3, ChoiceOption.list(choice["options"]).size, "a choice starts with three options")

        val check = column("type" to "check", "name" to "Зарядка")
        assertEquals(mapOf("type" to "count"), typeFields(check, ColumnType.Count, newId), "no cell is rewritten, nothing else changes")
        assertEquals(setOf("type", "options"), typeFields(check, ColumnType.Choice, newId).keys)
        val withOptions = column("type" to "check", "options" to ChoiceOption.write(listOf(ChoiceOption("a", "А"))))
        assertEquals(mapOf("type" to "choice"), typeFields(withOptions, ColumnType.Choice, newId), "its own options come back")
    }

    @Test
    fun inksGoRoundAndTypedNumbersAndClocksRead() {
        val palette = listOf("gray", "blue", "red")
        assertEquals("blue", nextInk(palette, listOf("gray", null)))
        assertEquals("blue", nextInk(palette, listOf("gray", "blue", "red", "gray")), "all taken: round again")
        assertEquals("2.5", typedNumber(" 2,5 "))
        assertNull(typedNumber("два"))
        assertEquals("8", numberText("8"))
        assertEquals("", numberText(null))
        assertEquals(21 * 60, typedClock("21"))
        assertEquals(9 * 60 + 5, typedClock("9:05"))
        assertNull(typedClock("24:00"))
        assertNull(typedClock("7:60"))
        assertNull(typedClock("1:2:3"))
        assertEquals("07:05", clockOf(7 * 60 + 5))
        assertEquals("emoji:💧", emojiToken(" 💧 "))
        assertNull(emojiToken("  "))
    }

    @Test
    fun aDraftWritesWhatChangedAndKeepsWhatItCantRead() {
        val water = column("type" to "count", "name" to "Вода", "icon" to "emoji:💧", "goal" to "8")
        val draft = ColumnDraft.of(water)
        assertEquals(emptyMap(), draft.changes(water), "opened and closed: nothing written")
        assertEquals(mapOf("name" to "Вода дома", "short" to "Вода"), draft.copy(name = " Вода дома ", short = "Вода").changes(water))
        assertEquals(emptyMap(), draft.copy(name = " ").changes(water), "a blank name keeps the old one")
        assertEquals(mapOf("goal" to "2.5"), draft.copy(goal = "2,5").changes(water))
        assertEquals(emptyMap(), draft.copy(goal = "много").changes(water), "a goal that doesn't read waits")
        assertEquals(mapOf("goal" to null), draft.copy(goal = "").changes(water))
        assertEquals(mapOf("icon" to null), draft.copy(emoji = "").changes(water))
        val drawn = column("type" to "check", "name" to "Сон", "icon" to "drawn:moon")
        assertEquals(emptyMap(), ColumnDraft.of(drawn).changes(drawn), "an icon of another scheme stays though the field is empty")

        val sleep = column("type" to "time", "name" to "Сон")
        assertEquals(mapOf("hours" to "21:00-11:00"), ColumnDraft.of(sleep).copy(from = "21", to = "11:00").changes(sleep))
        assertEquals(emptyMap(), ColumnDraft.of(sleep).copy(from = "21", to = "").changes(sleep), "half typed: wait")
        val night = column("type" to "time", "name" to "Сон", "hours" to "21:00-11:00")
        assertEquals("21:00", ColumnDraft.of(night).from)
        assertEquals(mapOf("hours" to null), ColumnDraft.of(night).copy(from = "", to = "").changes(night), "both empty: a day's total")

        val walks = column("type" to "check", "name" to "Прогулка")
        assertEquals(
            mapOf("goal" to "3", "goal_per" to "week"),
            ColumnDraft.of(walks).copy(goal = "3").changes(walks),
            "a check counts days",
        )
        val options = listOf(ChoiceOption("a", "А", "red"))
        assertEquals(setOf("options"), ColumnDraft.of(walks).copy(options = options).changes(walks).keys)
    }

    @Test
    fun aTrackersDraftAndThePresets() {
        val tracker = TrackerRecord("t", mapOf("name" to "Привычки", "icon" to "emoji:🌱"))
        assertEquals(TrackerDraft("Привычки", "🌱"), TrackerDraft.of(tracker))
        assertEquals(mapOf("name" to "Утро", "icon" to "emoji:🌞"), TrackerDraft("Утро", "🌞").changes(tracker))
        assertEquals(mapOf("icon" to null), TrackerDraft("Привычки", "").changes(tracker))
        assertEquals(emptyMap(), TrackerDraft("", "🌱").changes(tracker))

        assertEquals(listOf("habits", "water", "reading", "sport", "sleep", "time"), TrackerPresets.all.map { it.id })
        for (preset in TrackerPresets.all + TrackerPresets.blank) {
            assertTrue(preset.columns.all { ColumnType.of(it["type"]) != null }, "${preset.id}: types this app knows")
            assertTrue(preset.tracker["icon"]?.let(::iconEmoji) != null, "${preset.id}: an emoji")
        }
        assertTrue(TrackerPresets.blank.columns.isEmpty())
        val training = TrackerColumn(TrackerRecord("x", TrackerPresets.sport.columns.first()))
        assertEquals(3.0, training.goal)
    }
}
