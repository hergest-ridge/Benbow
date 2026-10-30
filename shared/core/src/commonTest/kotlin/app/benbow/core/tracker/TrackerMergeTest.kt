package app.benbow.core.tracker

import app.benbow.core.sync.Hlc
import app.benbow.core.sync.HlcClock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tracker records merged field by field, the newest HLC winning, as the calendar store merges every record (ADR-027):
 * whatever order the ops come in, some lost and sent again, devices end with the same records; and the rules made for
 * trackers keep every input (ADR-050): a day's cell is one record on every device, a count adds up every device's taps.
 * (The channel itself is simulated against the store in B2.)
 */
class TrackerMergeTest {
    private class Op(val kind: String, val id: String, val fields: Map<String, String?>, val hlc: Hlc)

    private class Device(val name: String, private val rnd: Random) {
        private var time = 1_000L
        private val clock = HlcClock(name) { time }
        val records = HashMap<Pair<String, String>, MutableMap<String, Pair<String?, Hlc>>>()

        fun write(kind: String, id: String, fields: Map<String, String?>): Op {
            time += rnd.nextLong(-30, 60) // a drifting clock
            return Op(kind, id, fields, clock.next()).also(::apply)
        }

        fun apply(op: Op) {
            clock.observe(op.hlc)
            val record = records.getOrPut(op.kind to op.id) { HashMap() }
            for ((field, value) in op.fields) {
                if (record[field]?.let { op.hlc > it.second } != false) record[field] = value to op.hlc
            }
        }

        fun fields(kind: String, id: String): Map<String, String?> = records[kind to id]?.mapValues { it.value.first }.orEmpty()
    }

    @Test
    fun anyOrderEndsTheSameAndEveryTapCounts() {
        repeat(300) { seed ->
            val rnd = Random(seed)
            val devices = List(3) { Device("d$it", rnd) }
            val ops = mutableListOf<Op>()
            val taps = HashMap<String, Int>()
            repeat(80) { step ->
                val device = devices.random(rnd)
                if (rnd.nextInt(4) == 0) ops.shuffled(rnd).take(rnd.nextInt(ops.size + 1)).forEach(device::apply) // a pull
                val day = "2026-10-2${rnd.nextInt(3)}"
                val water = TrackerCells.id("water", day)
                val walk = TrackerCells.id("walk", day)
                ops += when (rnd.nextInt(4)) {
                    0, 1 -> {
                        taps[water] = (taps[water] ?: 0) + 1
                        val add = TrackerCells.add(device.fields(TrackerKind.CELL, water), device.name, 1.0)
                        device.write(TrackerKind.CELL, water, TrackerCells.create("water", day) + add)
                    }

                    2 -> device.write(
                        TrackerKind.CELL,
                        walk,
                        TrackerCells.create("walk", day) + (CellField.VALUE to "true".takeIf { rnd.nextBoolean() }),
                    )

                    else -> device.write(TrackerKind.CELL, walk, mapOf(CellField.NOTE to "note $step"))
                }
            }
            // Every device gets every op at last, in its own order, some twice (a lost response sent again).
            devices.forEach { device -> (ops + ops.filter { rnd.nextInt(5) == 0 }).shuffled(rnd).forEach(device::apply) }

            assertTrue(devices.all { it.records == devices[0].records }, "seed $seed: the devices end the same")
            for ((cell, count) in taps) {
                assertEquals(
                    count.toDouble(),
                    TrackerCells.count(devices[0].fields(TrackerKind.CELL, cell)),
                    "seed $seed: every tap on $cell",
                )
            }
            val cells = devices[0].records.keys.map { it.second }
            assertEquals(cells.distinct().size, cells.size, "seed $seed: one record per column and day")
        }
    }

    @Test
    fun aCountSetOnOneDeviceKeepsTheTapsTheOtherMadeMeanwhile() {
        val phone = Device("phone", Random(1))
        val pc = Device("pc", Random(2))
        val cell = TrackerCells.id("water", "2026-10-27")
        val first = phone.write(TrackerKind.CELL, cell, TrackerCells.add(emptyMap(), "phone", 3.0))
        pc.apply(first)
        val set = pc.write(TrackerKind.CELL, cell, TrackerCells.setTotal(pc.fields(TrackerKind.CELL, cell), "pc", 5.0))
        val tap = phone.write(TrackerKind.CELL, cell, TrackerCells.add(phone.fields(TrackerKind.CELL, cell), "phone", 1.0))
        pc.apply(tap)
        phone.apply(set)
        assertEquals(6.0, TrackerCells.count(phone.fields(TrackerKind.CELL, cell)), "five as set, and the tap made meanwhile")
        assertEquals(phone.records, pc.records)
    }
}
