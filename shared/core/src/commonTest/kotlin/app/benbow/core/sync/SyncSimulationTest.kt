package app.benbow.core.sync

import app.benbow.testing.InMemorySyncStore
import app.benbow.testing.SyncSimulation
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Runs the shared sync simulation against the reference in-memory store (the server runs it on SQLite). */
class SyncSimulationTest {
    @Test
    fun randomHistoriesConvergeWithoutLosingData() = runTest {
        val simulation = SyncSimulation(::InMemorySyncStore)
        checkAll(Arb.long()) { seed -> simulation.run(seed) }
        simulation.assertInterestingPathsCovered()
    }
}
