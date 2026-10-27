package app.benbow.app

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.roundToInt
import kotlin.random.Random

// The trackers' B0 spike (ADR-050): made-up sheets to feel the vertical month before any data exists. Plain data and
// rules, apart from the screen (TrackerSpikeScreen.kt); nothing here is stored or synced.

/** What a column's cells hold (data-model.md "Trackers"; the note type comes with the real records). */
internal enum class SpikeType { Check, Count, Scale, Choice, Time }

/** One of a choice column's own options: cells hold its [id], so renaming or recoloring it changes no cell. */
internal data class SpikeOption(val id: String, val name: String, val color: String, val emoji: String)

/**
 * A column: [name] is its header text, [short] the header where it is narrow; [icon] is a look token (`emoji:…`) and
 * [color] an editor palette token, as the real records will hold them. [goal] fills a count's cell once reached; [max]
 * is a rating's top. [hours] are a time column's clock hours, one cell each, on the sleep sheet.
 */
internal data class SpikeColumn(
    val id: String,
    val name: String,
    val short: String,
    val icon: String,
    val color: String,
    val type: SpikeType,
    val goal: Int? = null,
    val max: Int = 5,
    val options: List<SpikeOption> = emptyList(),
    val hours: List<Int> = emptyList(),
)

/**
 * A tracker: its name, icon and columns, and its own look as a note has (ADR-047): [color] a Keep swatch (null: the
 * page's own) and [look] its layers' tokens by field (paper, pattern, light, motion, scrap).
 */
internal data class SpikeTracker(
    val id: String,
    val name: String,
    val icon: String,
    val columns: List<SpikeColumn>,
    val color: String? = null,
    val look: Map<String, String> = emptyMap(),
) {
    /** «Оформление трекера»: layers set at once, null taking one off, as a note's look is set. */
    fun withLook(layers: Map<String, String?>): SpikeTracker =
        copy(look = layers.entries.fold(look) { all, (layer, token) -> if (token == null) all - layer else all + (layer to token) })
}

/** The emoji of an `emoji:` icon token; other schemes (a hand-drawn set) come later and give none here. */
internal fun iconEmoji(icon: String): String? = if (icon.startsWith(EMOJI)) icon.removePrefix(EMOJI).ifEmpty { null } else null

private const val EMOJI = "emoji:"

/** The id a day's cell will have (ADR-050): two devices marking the same day meet in one record. */
internal fun cellId(column: SpikeColumn, day: LocalDate) = "${column.id}:$day"

/**
 * A tracker with its marks by [cellId]. A time column's value is the hours slept in the night that ends on that day
 * (sleep counts for the day one wakes), as indexes into its [SpikeColumn.hours], comma-separated.
 */
internal data class SpikeSheet(val tracker: SpikeTracker, val values: Map<String, String> = emptyMap()) {
    fun value(column: SpikeColumn, day: LocalDate): String? = values[cellId(column, day)]

    /** A tap: a check flips, a count adds one, a rating and a choice go to the next value and then back to empty. */
    fun tapped(column: SpikeColumn, day: LocalDate): SpikeSheet {
        val now = value(column, day)
        val next = when (column.type) {
            SpikeType.Check -> if (now == null) "true" else null
            SpikeType.Count -> ((now?.toIntOrNull() ?: 0) + 1).toString()
            SpikeType.Scale -> (now?.toIntOrNull() ?: 0).let { if (it >= column.max) null else (it + 1).toString() }
            SpikeType.Choice -> column.options.getOrNull(column.options.indexOfFirst { it.id == now } + 1)?.id
            SpikeType.Time -> return this
        }
        return set(column, day, next)
    }

    /** A long press empties the cell (the real sheet opens the exact number and a note there). */
    fun cleared(column: SpikeColumn, day: LocalDate): SpikeSheet = set(column, day, null)

    /** The sleep sheet: a tap on an hour paints or clears it (dragging along the row comes with B6). */
    fun hourTapped(column: SpikeColumn, day: LocalDate, hour: Int): SpikeSheet {
        val painted = slept(column, day)
        val next = if (hour in painted) painted - hour else painted + hour
        return set(column, day, next.sorted().joinToString(",").ifEmpty { null })
    }

    /** The painted hours of the night that ends on [day]. */
    fun slept(column: SpikeColumn, day: LocalDate): Set<Int> =
        value(column, day)?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet().orEmpty()

    private fun set(column: SpikeColumn, day: LocalDate, value: String?): SpikeSheet {
        val id = cellId(column, day)
        return copy(values = if (value == null) values - id else values + (id to value))
    }
}

/** How full a cell is drawn, 0 to 1: a count by its goal (full once reached), a rating by its level. */
internal fun SpikeColumn.fill(value: String?): Float = when (type) {
    SpikeType.Check -> if (value == null) 0f else 1f

    SpikeType.Count -> {
        val n = value?.toIntOrNull() ?: 0
        when {
            goal != null -> (n.toFloat() / goal).coerceIn(0f, 1f)
            n > 0 -> 1f
            else -> 0f
        }
    }

    SpikeType.Scale -> ((value?.toIntOrNull() ?: 0).toFloat() / max).coerceIn(0f, 1f)

    SpikeType.Choice -> if (options.any { it.id == value }) 1f else 0f

    SpikeType.Time -> 0f
}

/**
 * The column's figure at the foot of [days] (the month so far): checks counted, a count summed, a rating's and a
 * night's average, a choice's most frequent option; null while there is nothing. Figures only count up: no streaks,
 * percentages or missed days (ux.md "Psychology").
 */
internal fun SpikeSheet.total(column: SpikeColumn, days: List<LocalDate>): String? {
    val marked = days.mapNotNull { value(column, it) }
    if (marked.isEmpty()) return null
    return when (column.type) {
        SpikeType.Check -> marked.size.toString()

        SpikeType.Count -> marked.sumOf { it.toIntOrNull() ?: 0 }.toString()

        SpikeType.Scale -> decimal(marked.mapNotNull { it.toIntOrNull() }.average())

        SpikeType.Choice -> {
            val top = marked.groupingBy { it }.eachCount().maxBy { it.value }
            column.options.find { it.id == top.key }?.let { "${it.emoji} ${top.value}" }
        }

        SpikeType.Time -> nightLength(days.map { slept(column, it).size }.filter { it > 0 }.average())
    }
}

/** A night's length in the sheet's figures, "7:30" (hours as a Double). */
internal fun nightLength(hours: Double): String {
    val minutes = (hours * 60).roundToInt()
    return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
}

/** "3,4", or "3" for a whole number: one decimal with a comma, as Russian writes it. */
internal fun decimal(value: Double): String {
    val tenths = (value * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"
}

/** Every day of [month]'s month, the 1st first. */
internal fun daysOf(month: LocalDate): List<LocalDate> {
    val first = LocalDate(month.year, month.month, 1)
    val next = first.plus(1, DateTimeUnit.MONTH)
    return generateSequence(first) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it < next }.toList()
}

private val DAY_OPTIONS = listOf(
    SpikeOption("hard", "Тяжёлый", "red", "😫"),
    SpikeOption("plain", "Обычный", "gray", "😐"),
    SpikeOption("good", "Хороший", "green", "🙂"),
    SpikeOption("great", "Отличный", "blue", "🤩"),
)

private val REST_OPTIONS = listOf(
    SpikeOption("bad", "Плохо", "red", "😫"),
    SpikeOption("so", "Так себе", "yellow", "😐"),
    SpikeOption("fine", "Хорошо", "green", "😊"),
)

/** The vertical habit tracker: days down the page, habits across with their icons. */
internal val SPIKE_HABITS = SpikeTracker(
    "habits",
    "Привычки",
    "emoji:🌱",
    listOf(
        SpikeColumn("stretch", "Зарядка", "Зарядка", "emoji:🧘", "green", SpikeType.Check),
        SpikeColumn("walk", "Прогулка", "Прогулка", "emoji:🚶", "teal", SpikeType.Check),
        SpikeColumn("read", "Чтение", "Чтение", "emoji:📖", "purple", SpikeType.Check),
        SpikeColumn("water", "Вода, стаканы", "Вода", "emoji:💧", "blue", SpikeType.Count, goal = 8),
        SpikeColumn("offline", "Без соцсетей", "Без соц.", "emoji:📵", "orange", SpikeType.Check),
        SpikeColumn("energy", "Энергия", "Энергия", "emoji:🔋", "yellow", SpikeType.Scale),
        SpikeColumn("day", "Какой был день", "День", "emoji:🌈", "gray", SpikeType.Choice, options = DAY_OPTIONS),
    ),
    color = "sand",
    look = mapOf("paper" to "dots"),
)

/** The sleep sheet: days × the night's hours, from nine in the evening to ten in the morning, and how it went. */
internal val SPIKE_SLEEP = SpikeTracker(
    "sleep",
    "Сон",
    "emoji:😴",
    listOf(
        SpikeColumn(
            "sleep",
            "Сон",
            "Сон",
            "emoji:🌙",
            "blue",
            SpikeType.Time,
            hours = listOf(21, 22, 23, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
        ),
        SpikeColumn("rest", "Как спалось", "Как", "emoji:💤", "green", SpikeType.Choice, options = REST_OPTIONS),
    ),
    color = "fog",
)

/** The spike's trackers, marked at random (the same every run) from three months back to [today]; today is left blank. */
internal fun spikeSheets(today: LocalDate): List<SpikeSheet> {
    val random = Random(47)
    val from = LocalDate(today.year, today.month, 1).minus(3, DateTimeUnit.MONTH)
    val days = generateSequence(from) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it < today }.toList()
    return listOf(habits(days, random), sleep(days, random))
}

private fun habits(days: List<LocalDate>, random: Random): SpikeSheet {
    val columns = SPIKE_HABITS.columns.associateBy { it.id }
    val values = mutableMapOf<String, String>()
    fun mark(column: String, day: LocalDate, value: String?) {
        if (value != null) values[cellId(columns.getValue(column), day)] = value
    }
    for (day in days) {
        val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
        mark("stretch", day, "true".takeIf { random.chance(if (weekend) 0.35 else 0.6) })
        mark("walk", day, "true".takeIf { random.chance(0.7) })
        mark("read", day, "true".takeIf { random.chance(if (weekend) 0.7 else 0.45) })
        mark("water", day, (3 + random.nextInt(8)).toString().takeIf { random.chance(0.9) })
        mark("offline", day, "true".takeIf { random.chance(0.35) })
        mark("energy", day, (2 + random.nextInt(4)).toString().takeIf { random.chance(0.85) })
        mark("day", day, DAY_OPTIONS[pick(random, 1, 4, 6, 2)].id.takeIf { random.chance(0.8) })
    }
    return SpikeSheet(SPIKE_HABITS, values)
}

private fun sleep(days: List<LocalDate>, random: Random): SpikeSheet {
    val (night, rest) = SPIKE_SLEEP.columns
    val values = mutableMapOf<String, String>()
    for (day in days) {
        if (random.chance(0.1)) continue // a night left blank
        val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
        val bed = listOf(22, 23, 0, 1)[pick(random, 1, 4, 3, 1)]
        val wake = if (weekend) 8 + random.nextInt(2) else 6 + random.nextInt(2)
        val from = night.hours.indexOf(bed)
        val to = night.hours.indexOf(wake)
        values[cellId(night, day)] = (from until to).joinToString(",")
        if (random.chance(0.8)) values[cellId(rest, day)] = REST_OPTIONS[pick(random, 1, 3, 4)].id
    }
    return SpikeSheet(SPIKE_SLEEP, values)
}

private fun Random.chance(p: Double) = nextDouble() < p

/** An index chosen by [weights]. */
private fun pick(random: Random, vararg weights: Int): Int {
    var left = random.nextInt(weights.sum())
    weights.forEachIndexed { i, weight ->
        if (left < weight) return i
        left -= weight
    }
    return weights.lastIndex
}
