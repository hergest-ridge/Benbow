package app.benbow.app

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The trackers' B0 spike (ADR-050): what a tap does to each kind of cell, and the figures at a month's foot. */
class TrackerSpikeLogicTest {
    private val day = LocalDate(2026, 10, 27)
    private val columns = SPIKE_HABITS.columns.associateBy { it.id }
    private val stretch = columns.getValue("stretch")
    private val water = columns.getValue("water")
    private val energy = columns.getValue("energy")
    private val mood = columns.getValue("day")
    private val night = SPIKE_SLEEP.columns.first()

    @Test
    fun aTapFlipsACheckAddsToACountAndStepsThroughARatingAndAChoice() {
        var sheet = SpikeSheet(SPIKE_HABITS)
        sheet = sheet.tapped(stretch, day)
        assertEquals("true", sheet.value(stretch, day))
        assertNull(sheet.tapped(stretch, day).value(stretch, day), "a second tap takes it off")

        repeat(3) { sheet = sheet.tapped(water, day) }
        assertEquals("3", sheet.value(water, day))
        assertNull(sheet.cleared(water, day).value(water, day), "a long press empties it")

        val ratings = generateSequence(sheet.tapped(energy, day)) { it.tapped(energy, day) }.take(6).map { it.value(energy, day) }
        assertEquals(listOf("1", "2", "3", "4", "5", null), ratings.toList(), "past the top it is empty again")

        val choices = generateSequence(sheet.tapped(mood, day)) { it.tapped(mood, day) }.take(5).map { it.value(mood, day) }
        assertEquals(listOf("hard", "plain", "good", "great", null), choices.toList())
        assertEquals(setOf("stretch:2026-10-27", "water:2026-10-27"), sheet.values.keys, "one cell per column and day")
    }

    @Test
    fun aNightIsPaintedHourByHour() {
        var sheet = SpikeSheet(SPIKE_SLEEP)
        listOf(3, 4, 5).forEach { sheet = sheet.hourTapped(night, day, it) }
        assertEquals(setOf(3, 4, 5), sheet.slept(night, day))
        assertEquals("3,4,5", sheet.value(night, day))
        sheet = listOf(3, 4, 5).fold(sheet) { s, hour -> s.hourTapped(night, day, hour) }
        assertNull(sheet.value(night, day), "a night with no hours left is blank, not an empty value")
        assertEquals(sheet, sheet.tapped(night, day), "a tap on the whole night does nothing")
    }

    @Test
    fun aCountFillsTowardsItsGoalAndARatingByItsLevel() {
        assertEquals(0f, water.fill(null))
        assertEquals(0.5f, water.fill("4"))
        assertEquals(1f, water.fill("8"), "full once the goal is reached")
        assertEquals(1f, water.fill("11"))
        assertEquals(0.6f, energy.fill("3"))
        assertEquals(1f, stretch.fill("true"))
        assertEquals(0f, mood.fill("nonsense"), "a value that is no option draws nothing (and is kept)")
    }

    @Test
    fun theFootCountsUpAveragesAndNamesTheMostFrequentChoice() {
        val days = (1..4).map { LocalDate(2026, 10, it) }
        var sheet = SpikeSheet(SPIKE_HABITS)
        days.take(3).forEach { sheet = sheet.tapped(stretch, it) }
        days.forEachIndexed { i, d -> repeat(i + 1) { sheet = sheet.tapped(water, d) } }
        listOf(3, 4, 4, 2).zip(days).forEach { (level, d) -> repeat(level) { sheet = sheet.tapped(energy, d) } }
        listOf(2, 3, 3).zip(days).forEach { (steps, d) -> repeat(steps) { sheet = sheet.tapped(mood, d) } }
        assertEquals("3", sheet.total(stretch, days))
        assertEquals("10", sheet.total(water, days))
        assertEquals("3,3", sheet.total(energy, days))
        assertEquals("🙂 2", sheet.total(mood, days))
        assertNull(sheet.total(stretch, listOf(LocalDate(2026, 9, 1))), "nothing marked: no figure, not a zero")

        var nights = SpikeSheet(SPIKE_SLEEP)
        (3 until 11).forEach { nights = nights.hourTapped(night, days[0], it) }
        (2 until 9).forEach { nights = nights.hourTapped(night, days[1], it) }
        assertEquals("7:30", nights.total(night, days), "the average of the nights slept; blank nights don't count")
    }

    @Test
    fun figuresReadAsRussianWritesThem() {
        assertEquals("3,4", decimal(3.36))
        assertEquals("4", decimal(4.0))
        assertEquals("7:05", nightLength(7 + 5 / 60.0))
        assertEquals(31, daysOf(LocalDate(2026, 10, 17)).size)
        assertEquals(LocalDate(2026, 2, 28), daysOf(LocalDate(2026, 2, 3)).last())
    }

    @Test
    fun theMadeUpSheetsAreTheSameEveryRunAndLeaveTodayBlank() {
        val first = spikeSheets(day)
        assertEquals(first, spikeSheets(day))
        assertEquals(listOf("habits", "sleep"), first.map { it.tracker.id })
        assertTrue(first.all { sheet -> sheet.values.keys.none { it.endsWith(":$day") } }, "today waits for the owner")
        assertTrue(first.all { sheet -> sheet.values.keys.any { it.endsWith(":2026-07-01") } }, "three months back")
        assertTrue(first[1].values.filterKeys { it.startsWith("sleep:") }.values.all { it.split(',').size in 5..11 })
        assertEquals("🌱", iconEmoji(SPIKE_HABITS.icon))
        assertNull(iconEmoji("drawn:leaf"), "a later icon scheme gives no emoji")
        assertEquals("sleep:2026-10-27", cellId(night, day))
    }
}
