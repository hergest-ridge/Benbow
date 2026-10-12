package app.benbow.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SyncControllerTest {
    private val pending = MutableStateFlow(0L)
    private var calls = 0
    private val results = ArrayDeque<SyncResult>()

    private fun TestScope.controller() = SyncController(
        scope = backgroundScope,
        sync = {
            calls++
            results.removeFirstOrNull() ?: SyncResult.Done(0, 0, 0)
        },
        pending = pending,
        rejected = flowOf(0),
        now = { currentTime },
    ).also {
        it.start()
        runCurrent()
    }

    @Test
    fun localChangesSyncAfterAShortPause() = runTest {
        controller()
        assertEquals(0, calls, "in the background nothing syncs on its own")
        pending.value = 2
        advanceTimeBy(1_900.milliseconds)
        assertEquals(0, calls)
        advanceTimeBy(200.milliseconds)
        assertEquals(1, calls)
    }

    @Test
    fun failuresBackOffThenRecover() = runTest {
        results += listOf(SyncResult.Failed("offline"), SyncResult.Failed("offline"))
        val controller = controller()
        controller.requestSync()
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(3.9.seconds)
        assertEquals(1, calls, "first retry after ~5 s")
        advanceTimeBy(2.2.seconds)
        assertEquals(2, calls)
        advanceTimeBy(12.1.seconds)
        assertEquals(3, calls, "second retry after ~10 s")
        assertIs<SyncResult.Done>(controller.status.value.last)
        assertTrue(controller.status.value.lastSuccessAt!! > 10_000)
    }

    @Test
    fun foregroundSyncsNowAndPeriodically() = runTest {
        val controller = controller()
        controller.foreground = true
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(2.minutes + 1.milliseconds)
        assertEquals(2, calls)
        controller.foreground = false
        advanceTimeBy(2.minutes + 1.milliseconds)
        advanceTimeBy(30.minutes)
        assertEquals(3, calls, "the timer already running fires once, then it stops")
    }
}
