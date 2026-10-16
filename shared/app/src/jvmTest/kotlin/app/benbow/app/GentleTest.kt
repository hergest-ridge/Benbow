package app.benbow.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GentleTest {
    private var time = 0L

    @Test
    fun turnedOffItComesBackAfterFiveMinutesAwayAndInEveryNewProcess() {
        val gentle = Gentle { time }
        assertTrue(gentle.on.value, "on from the start")
        gentle.set(false)

        gentle.onLeave()
        time += Gentle.AWAY_MILLIS - 1
        gentle.onReturn()
        assertFalse(gentle.on.value, "back within five minutes: still open")

        time += 60 * 60_000 // an hour on screen doesn't count as away
        gentle.onReturn()
        assertFalse(gentle.on.value)

        gentle.onLeave()
        time += Gentle.AWAY_MILLIS
        gentle.onReturn()
        assertTrue(gentle.on.value, "away for five minutes: closed again")

        gentle.set(false)
        assertTrue(Gentle { time }.on.value, "a new process starts closed")
    }
}
