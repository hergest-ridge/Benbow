package app.benbow.core.calendar

/** The built-in calendar of events made in Benbow («Мои события»); it has no record until it is renamed or recolored. */
const val MY_CALENDAR = "mine"

/** Its name until it is renamed, in exports made without the app's strings (the server's mirror, the ZIP). */
const val MY_CALENDAR_NAME = "Мои события"

/** Fields of an event record (docs/data-model.md#calendar). */
object EventField {
    const val CALENDAR = "calendar"
    const val TITLE = "title"
    const val START = "start"
    const val END = "end"
    const val DESCRIPTION = "description"
    const val LOCATION = "location"
    const val RRULE = "rrule"

    /** Days the series skips, "YYYY-MM-DD" separated by commas. */
    const val EXDATE = "exdate"

    /** The ICS UID it came with (7.3d); exports keep it, so other calendars recognise the event. */
    const val UID = "uid"

    /** The import batch it came in with (7.3d), so "Отменить импорт" finds it; calendars made by an import carry it too. */
    const val IMPORT = "import"
    const val DELETED = "deleted"
}

/** Fields of a calendar record. */
object CalendarField {
    const val NAME = "name"
    const val COLOR = "color"

    /** "false" hides it (the checkbox); absent = shown. */
    const val VISIBLE = "visible"
    const val DELETED = "deleted"
}
