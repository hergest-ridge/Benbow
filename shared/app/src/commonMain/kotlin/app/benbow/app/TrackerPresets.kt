package app.benbow.app

import app.benbow.core.tracker.ChoiceOption
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.DayBy
import app.benbow.core.tracker.GoalPer
import app.benbow.core.tracker.TrackerField

/**
 * A tracker to start from (ADR-050): its fields and its columns' fields, in order. Everything in them can be changed
 * after, in «Настроить трекер»; [blank] is a sheet with no columns that opens there at once.
 */
internal class TrackerPreset(val id: String, val tracker: Map<String, String?>, val columns: List<Map<String, String?>>)

internal object TrackerPresets {
    /** The vertical habit tracker: days down the page, habits across with their icons. */
    val habits = TrackerPreset(
        "habits",
        mapOf(
            TrackerField.NAME to "Привычки",
            TrackerField.ICON to "emoji:🌱",
            TrackerField.VIEW to "month",
            TrackerField.BACKGROUND to "sand",
            TrackerField.LOOK_PREFIX + "paper" to "dots",
            TrackerField.PRESET to "habits",
        ),
        listOf(
            column("Зарядка", "emoji:🧘", "green", ColumnType.Check),
            column("Прогулка", "emoji:🚶", "teal", ColumnType.Check),
            column("Чтение", "emoji:📖", "purple", ColumnType.Check),
            column("Вода, стаканы", "emoji:💧", "blue", ColumnType.Count, short = "Вода", extra = mapOf(ColumnField.GOAL to "8")),
            column("Без соцсетей", "emoji:📵", "orange", ColumnType.Check, short = "Без соц."),
            column("Энергия", "emoji:🔋", "yellow", ColumnType.Scale),
            column(
                "Какой был день",
                "emoji:🌈",
                "gray",
                ColumnType.Choice,
                short = "День",
                extra = mapOf(
                    ColumnField.OPTIONS to ChoiceOption.write(
                        listOf(
                            ChoiceOption("hard", "Тяжёлый", "red", "emoji:😫"),
                            ChoiceOption("plain", "Обычный", "gray", "emoji:😐"),
                            ChoiceOption("good", "Хороший", "green", "emoji:🙂"),
                            ChoiceOption("great", "Отличный", "blue", "emoji:🤩"),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** The sleep sheet: days × the night's hours from nine in the evening to eleven in the morning, and how it went. */
    val sleep = TrackerPreset(
        "sleep",
        mapOf(
            TrackerField.NAME to "Сон",
            TrackerField.ICON to "emoji:😴",
            TrackerField.VIEW to "sleep",
            TrackerField.BACKGROUND to "fog",
            TrackerField.PRESET to "sleep",
        ),
        listOf(
            column(
                "Сон",
                "emoji:🌙",
                "blue",
                ColumnType.Time,
                extra = mapOf(ColumnField.HOURS to "21:00-11:00", ColumnField.DAY_BY to DayBy.End.token),
            ),
            column(
                "Как спалось",
                "emoji:💤",
                "green",
                ColumnType.Choice,
                short = "Как",
                extra = mapOf(
                    ColumnField.OPTIONS to ChoiceOption.write(
                        listOf(
                            ChoiceOption("bad", "Плохо", "red", "emoji:😫"),
                            ChoiceOption("so", "Так себе", "yellow", "emoji:😐"),
                            ChoiceOption("fine", "Хорошо", "green", "emoji:😊"),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** Glasses of water a day, filling up towards eight. */
    val water = TrackerPreset(
        "water",
        mapOf(
            TrackerField.NAME to "Вода",
            TrackerField.ICON to "emoji:💧",
            TrackerField.VIEW to "month",
            TrackerField.BACKGROUND to "fog",
            TrackerField.PRESET to "water",
        ),
        listOf(
            column("Вода", "emoji:💧", "blue", ColumnType.Count, extra = mapOf(ColumnField.UNIT to "стаканов", ColumnField.GOAL to "8")),
            column("Чай и кофе", "emoji:🍵", "brown", ColumnType.Count, short = "Чай", extra = mapOf(ColumnField.UNIT to "чашек")),
        ),
    )

    /** Reading: whether one read, how many pages, and which book. */
    val reading = TrackerPreset(
        "reading",
        mapOf(
            TrackerField.NAME to "Чтение",
            TrackerField.ICON to "emoji:📚",
            TrackerField.VIEW to "month",
            TrackerField.BACKGROUND to "clay",
            TrackerField.LOOK_PREFIX + "paper" to "lines",
            TrackerField.PRESET to "reading",
        ),
        listOf(
            column("Читал(а)", "emoji:📖", "purple", ColumnType.Check, short = "Читал"),
            column("Страницы", "emoji:📄", "blue", ColumnType.Count, short = "Стр.", extra = mapOf(ColumnField.UNIT to "стр.")),
            column("Книга", "emoji:📚", "brown", ColumnType.Note),
        ),
    )

    /** Sport: training days towards three a week, steps, stretching and how one felt. */
    val sport = TrackerPreset(
        "sport",
        mapOf(
            TrackerField.NAME to "Спорт",
            TrackerField.ICON to "emoji:🏃",
            TrackerField.VIEW to "month",
            TrackerField.BACKGROUND to "mint",
            TrackerField.PRESET to "sport",
        ),
        listOf(
            column(
                "Тренировка",
                "emoji:💪",
                "red",
                ColumnType.Check,
                short = "Трен.",
                extra = mapOf(ColumnField.GOAL to "3", ColumnField.GOAL_PER to GoalPer.Week.token),
            ),
            column("Шаги, тысяч", "emoji:🚶", "green", ColumnType.Count, short = "Шаги", extra = mapOf(ColumnField.UNIT to "тыс.")),
            column("Растяжка", "emoji:🧘", "teal", ColumnType.Check),
            column("Самочувствие", "emoji:🔋", "yellow", ColumnType.Scale, short = "Сила"),
        ),
    )

    /** Time on what matters, by timer: one at a time, a day's total in each cell. */
    val time = TrackerPreset(
        "time",
        mapOf(
            TrackerField.NAME to "Время",
            TrackerField.ICON to "emoji:🕒",
            TrackerField.VIEW to "month",
            TrackerField.BACKGROUND to "storm",
            TrackerField.PRESET to "time",
        ),
        listOf(
            column("Работа", "emoji:💻", "blue", ColumnType.Time, extra = mapOf(ColumnField.TIMER to "one")),
            column("Учёба", "emoji:🧠", "purple", ColumnType.Time, extra = mapOf(ColumnField.TIMER to "one")),
            column("Своё дело", "emoji:🎨", "orange", ColumnType.Time, short = "Своё", extra = mapOf(ColumnField.TIMER to "one")),
        ),
    )

    /** A sheet with no columns: «Настроить трекер» opens on it. */
    val blank = TrackerPreset(
        "blank",
        mapOf(TrackerField.NAME to "Мой трекер", TrackerField.ICON to "emoji:📋", TrackerField.VIEW to "month"),
        emptyList(),
    )

    /** The presets in the order «Новый трекер» offers them; [blank] comes apart, last. */
    val all = listOf(habits, water, reading, sport, sleep, time)

    private fun column(
        name: String,
        icon: String,
        color: String,
        type: ColumnType,
        short: String = name,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String?> = mapOf(
        ColumnField.NAME to name,
        ColumnField.SHORT to short,
        ColumnField.ICON to icon,
        ColumnField.COLOR to color,
        ColumnField.TYPE to type.token,
    ) + extra
}
