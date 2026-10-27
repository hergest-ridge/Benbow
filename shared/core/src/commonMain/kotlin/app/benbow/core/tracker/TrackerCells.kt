package app.benbow.core.tracker

import kotlin.math.abs
import kotlin.math.roundToLong

/** A day's cells (ADR-050): one record per column and day, its fields merged LWW, a count kept per device. */
object TrackerCells {
    /** `<column id>:<YYYY-MM-DD>`: the same on every device, so two devices marking one day meet in one record. */
    fun id(column: String, day: String) = "$column:$day"

    /** The fields of a new cell, written with its first value. */
    fun create(column: String, day: String): Map<String, String?> = mapOf(CellField.COLUMN to column, CellField.DAY to day)

    /** What a count's cell holds: the sum of its devices' parts. */
    fun count(fields: Map<String, String?>): Double =
        fields.entries.sumOf { (field, value) -> if (field.startsWith(CellField.ADD_PREFIX)) value?.toDoubleOrNull() ?: 0.0 else 0.0 }

    /**
     * What a tap of [delta] on [device] writes: that device's own part, nothing else, so taps on two devices (offline,
     * or at the same moment) both count once their parts meet.
     */
    fun add(fields: Map<String, String?>, device: String, delta: Double): Map<String, String?> {
        val own = CellField.ADD_PREFIX + device
        return mapOf(own to number((fields[own]?.toDoubleOrNull() ?: 0.0) + delta))
    }

    /** Setting the count to [total] on [device]: its own part becomes the total less what the others' parts hold. */
    fun setTotal(fields: Map<String, String?>, device: String, total: Double): Map<String, String?> {
        val own = CellField.ADD_PREFIX + device
        val others = count(fields) - (fields[own]?.toDoubleOrNull() ?: 0.0)
        return mapOf(own to number(total - others))
    }

    /** Whether the cell counts as marked in its column's figures and goals; a type this app doesn't know never does. */
    fun marked(type: ColumnType?, fields: Map<String, String?>): Boolean = when (type) {
        ColumnType.Check -> fields[CellField.VALUE] == "true"
        ColumnType.Count -> count(fields) > 0
        ColumnType.Scale -> fields[CellField.VALUE]?.toIntOrNull() != null
        ColumnType.Choice -> fields[CellField.VALUE] != null
        ColumnType.Note -> !fields[CellField.VALUE].isNullOrBlank()
        ColumnType.Time, null -> false
    }

    /** A decimal as cells keep it: "3", "2.5", "-1", to thousandths, never in exponent form. */
    fun number(value: Double): String {
        val thousandths = (value * 1000).roundToLong()
        val whole = abs(thousandths) / 1000
        val fraction = (abs(thousandths) % 1000).toString().padStart(3, '0').trimEnd('0')
        return (if (thousandths < 0) "-" else "") + whole + if (fraction.isEmpty()) "" else ".$fraction"
    }
}
