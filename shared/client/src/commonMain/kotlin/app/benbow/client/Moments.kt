package app.benbow.client

import app.benbow.client.db.BenbowDb
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Moving an entry in time (data-model.md): the LWW field `when` = local "YYYY-MM-DDTHH:MM". The day and creation time
 * it was made with never change (the server keeps them); every device shows the entry on the day and at the time of
 * `when`, by rewriting its local `day` and `created_at` columns after each local change and each pull.
 */
internal object Moments {
    const val FIELD = "when"

    fun format(moment: LocalDateTime): String =
        "${moment.date}T${moment.hour.toString().padStart(2, '0')}:${moment.minute.toString().padStart(2, '0')}"

    fun parse(value: String?): LocalDateTime? = value?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    /** Puts the entry's day and order where its `when` says; nothing to do without one. */
    fun apply(db: BenbowDb, id: String, fields: Map<String, String?>) {
        val moment = parse(fields[FIELD]) ?: return
        db.benbowQueries.moveEntry(moment.date.toString(), moment.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds(), id)
    }
}
