package app.benbow.client

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Home's soft stats (ADR-034): what was written, only ever counted up; no streaks, no comparisons. [days] and [moments]
 * are the month's (quiet entries count, as in reviews), [pictures] the photos and videos in them, [tags] the month's
 * most used tags (not feelings: those belong to the mood), [since] the journal's first day, [weekMood] the last seven
 * days' average mood.
 */
data class HomeStats(
    val days: Int = 0,
    val moments: Int = 0,
    val pictures: Int = 0,
    val tags: List<TagRef> = emptyList(),
    val since: String? = null,
    val weekMood: Double? = null,
)

/**
 * The stats for the month [monthFrom]..[monthTo] and the week [weekFrom]..[today]. With [gentle] (Бережно on) the
 * tags leave out curtained and quiet entries, so a tag never comes up on Home from them.
 */
fun JournalRepository.homeStats(monthFrom: String, monthTo: String, weekFrom: String, today: String, gentle: Boolean): Flow<HomeStats> =
    combine(
        q.statsEntries(minOf(monthFrom, weekFrom), maxOf(monthTo, today)).asFlow().mapToList(context),
        q.firstDay().asFlow().mapToOne(context),
        gentleRules(),
        tagRegistry(),
    ) { rows, first, rules, registry ->
        val month = rows.filter { it.day in monthFrom..monthTo }
        val uses = HashMap<String, Pair<TagRef, Int>>()
        month.forEach { row ->
            val fields = decodeFields(row.fields)
            val tags = tagsOf(fields)
            if (gentle &&
                rules.veilOf(Veil.of(fields[VEIL_FIELD]), fields[VEIL_UNTIL_FIELD], row.day, tags.map { it.id }) != null
            ) {
                return@forEach
            }
            registry.resolve(tags).filter { registry.category(it.id) != EMOTIONS }.forEach { tag ->
                uses[tag.id] = tag to (uses[tag.id]?.second ?: 0) + 1
            }
        }
        val moods = rows.filter { it.day in weekFrom..today }.mapNotNull { decodeFields(it.fields)[MOOD_FIELD]?.toIntOrNull() }
        HomeStats(
            days = month.mapTo(HashSet()) { it.day }.size,
            moments = month.size,
            pictures = month.sumOf { row ->
                decodeAttachments(row.attachments).values.count { it.present && it.meta.kind in PICTURE_KINDS }
            },
            tags = uses.values.sortedWith(compareByDescending<Pair<TagRef, Int>> { it.second }.thenBy { it.first.name.lowercase() })
                .take(TOP_TAGS).map { it.first },
            since = first.first,
            weekMood = moods.takeIf { it.isNotEmpty() }?.average(),
        )
    }

/** What a stats block can show; S shows one, M three. */
enum class StatFigure(val value: String) {
    Days("days"),
    Moments("moments"),
    Pictures("pictures"),
    Tags("tags"),
    Since("since"),
    Mood("mood"),
}

/** How many figures a stats block of this size shows. */
val BlockSize.figureCount: Int get() = if (this == BlockSize.S) 1 else 3

/** A stats block's figures in the owner's order: those it was given (unknown ones skipped), else days, moments, pictures. */
val HomeBlock.figures: List<StatFigure> get() {
    val chosen = config[HomeBlock.FIGURES]?.split(',')?.mapNotNull { value -> StatFigure.entries.firstOrNull { it.value == value } }
    return (chosen ?: listOf(StatFigure.Days, StatFigure.Moments, StatFigure.Pictures)).take((blockSize ?: BlockSize.M).figureCount)
}

private val PICTURE_KINDS = setOf("image", "video")
private const val TOP_TAGS = 3
