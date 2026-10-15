package app.benbow.core.calendar

/**
 * A date or a date-time as an ICS file writes it: "20261014" or "20261014T093000", in UTC ([utc]), in a zone
 * ([tzid]) or floating (neither: local wall time, as Benbow keeps it).
 */
data class IcsTime(val value: String, val utc: Boolean = false, val tzid: String? = null) {
    val isDate: Boolean get() = value.length == DATE_DIGITS
}

/** One VEVENT; times are as the file has them (converting zones is the importer's job, 7.3d). */
data class IcsEvent(
    val uid: String? = null,
    val summary: String = "",
    val description: String? = null,
    val location: String? = null,
    val start: IcsTime,
    val end: IcsTime? = null,
    /** DURATION as written ("PT1H30M"), when there is no DTEND. */
    val duration: String? = null,
    val rrule: String? = null,
    val exdates: List<IcsTime> = emptyList(),
    /** Set on a changed occurrence of a series (it overrides that day). */
    val recurrenceId: IcsTime? = null,
    val cancelled: Boolean = false,
)

/** One VCALENDAR: its name (X-WR-CALNAME), Benbow's color for it, the events, and how many VEVENTs couldn't be read. */
data class IcsCalendar(val name: String? = null, val color: String? = null, val events: List<IcsEvent> = emptyList(), val skipped: Int = 0)

/**
 * A small RFC 5545 reader and writer for what calendars exchange: VCALENDAR with VEVENTs. Lines are unfolded and
 * folded at 75 bytes (never inside a character), text is escaped, alarms, time zone definitions and to-dos are
 * skipped. Unreadable events are counted, never fatal.
 */
object Ics {
    const val PRODID = "-//Benbow//Calendar//RU"

    fun parse(text: String): List<IcsCalendar> {
        val calendars = mutableListOf<IcsCalendar>()
        var current: IcsCalendar? = null
        var event: MutableList<Line>? = null
        var skipDepth = 0
        for (raw in unfold(text)) {
            val line = parseLine(raw) ?: continue
            when {
                skipDepth > 0 -> when (line.name) {
                    "BEGIN" -> skipDepth++
                    "END" -> skipDepth--
                }

                line.name == "BEGIN" && line.value.equals("VCALENDAR", true) -> current = IcsCalendar()

                line.name == "END" && line.value.equals("VCALENDAR", true) -> {
                    current?.let(calendars::add)
                    current = null
                }

                line.name == "BEGIN" && line.value.equals("VEVENT", true) && current != null && event == null -> event = mutableListOf()

                line.name == "END" && line.value.equals("VEVENT", true) && event != null -> {
                    val read = event(event)
                    current = current?.let { if (read == null) it.copy(skipped = it.skipped + 1) else it.copy(events = it.events + read) }
                    event = null
                }

                line.name == "BEGIN" -> skipDepth = 1

                // VALARM, VTIMEZONE, VTODO, ...

                event != null -> event += line

                current != null -> current = when (line.name) {
                    "X-WR-CALNAME" -> current.copy(name = unescape(line.value))
                    "X-BENBOW-COLOR" -> current.copy(color = line.value.trim())
                    else -> current
                }
            }
        }
        return calendars
    }

    /** The calendar as an .ics file (CRLF lines); [stamp] is DTSTAMP, "YYYYMMDDTHHMMSSZ". */
    fun write(calendar: IcsCalendar, stamp: String): String = buildString {
        fold("BEGIN:VCALENDAR")
        fold("VERSION:2.0")
        fold("PRODID:$PRODID")
        fold("CALSCALE:GREGORIAN")
        calendar.name?.let { fold("X-WR-CALNAME:" + escape(it)) }
        calendar.color?.let { fold("X-BENBOW-COLOR:$it") }
        for (e in calendar.events) {
            fold("BEGIN:VEVENT")
            e.uid?.let { fold("UID:" + escape(it)) }
            fold("DTSTAMP:$stamp")
            fold(time("DTSTART", e.start))
            e.end?.let { fold(time("DTEND", it)) }
            e.duration?.let { fold("DURATION:$it") }
            e.rrule?.let { fold("RRULE:" + it.removePrefix("RRULE:")) }
            if (e.exdates.isNotEmpty()) {
                fold(time("EXDATE", e.exdates.first()).substringBefore(':') + ":" + e.exdates.joinToString(",") { it.text() })
            }
            e.recurrenceId?.let { fold(time("RECURRENCE-ID", it)) }
            fold("SUMMARY:" + escape(e.summary))
            e.description?.let { fold("DESCRIPTION:" + escape(it)) }
            e.location?.let { fold("LOCATION:" + escape(it)) }
            if (e.cancelled) fold("STATUS:CANCELLED")
            fold("END:VEVENT")
        }
        fold("END:VCALENDAR")
    }

    private data class Line(val name: String, val params: Map<String, String>, val value: String)

    private fun event(lines: List<Line>): IcsEvent? {
        // A single-valued property written twice: the first one counts.
        val first = lines.groupBy { it.name }.mapValues { it.value.first() }
        val start = first["DTSTART"]?.let(::time) ?: return null
        return IcsEvent(
            uid = first["UID"]?.value?.let(::unescape),
            summary = first["SUMMARY"]?.value?.let(::unescape).orEmpty(),
            description = first["DESCRIPTION"]?.value?.let(::unescape),
            location = first["LOCATION"]?.value?.let(::unescape),
            start = start,
            end = first["DTEND"]?.let { time(it) ?: return null },
            duration = first["DURATION"]?.value?.trim(),
            rrule = first["RRULE"]?.value?.trim(),
            exdates = lines.filter { it.name == "EXDATE" }.flatMap { line ->
                line.value.split(',').map { v -> time(line.copy(value = v.trim())) ?: return null }
            },
            recurrenceId = first["RECURRENCE-ID"]?.let { time(it) ?: return null },
            cancelled = first["STATUS"]?.value?.trim().equals("CANCELLED", ignoreCase = true),
        )
    }

    /** "20261014", "20261014T093000" or "20261014T093000Z", with an optional TZID; anything else isn't a time. */
    private fun time(line: Line): IcsTime? {
        val value = line.value.trim()
        val utc = value.endsWith("Z") || value.endsWith("z")
        val digits = value.removeSuffix("Z").removeSuffix("z")
        val ok = when (digits.length) {
            DATE_DIGITS -> digits.all { it.isDigit() }
            DATE_TIME_LENGTH -> digits[DATE_DIGITS] == 'T' && digits.removeRange(DATE_DIGITS, DATE_DIGITS + 1).all { it.isDigit() }
            else -> false
        }
        if (!ok || (utc && digits.length == DATE_DIGITS)) return null
        return IcsTime(digits, utc, line.params["TZID"]?.takeIf { !utc })
    }

    private fun time(name: String, t: IcsTime): String = name + (if (t.isDate) ";VALUE=DATE" else "") + (
        t.tzid?.let {
            ";TZID=" + if (it.any { c -> c in ",;:" }) "\"$it\"" else it
        } ?: ""
        ) +
        ":" +
        t.text()

    private fun IcsTime.text() = value + if (utc) "Z" else ""

    /** Joins folded lines (a line break followed by a space or a tab); empty lines are dropped. */
    private fun unfold(text: String): List<String> {
        val lines = ArrayList<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(' ') || raw.startsWith('\t')) && lines.isNotEmpty()) {
                lines[lines.lastIndex] = lines.last() + raw.substring(1)
            } else if (raw.isNotBlank()) {
                lines += raw
            }
        }
        return lines
    }

    /** NAME;PARAM=value;PARAM="quoted:value":VALUE (names case-insensitive). */
    private fun parseLine(line: String): Line? {
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) colon = i
            }
            if (colon >= 0) break
        }
        if (colon <= 0) return null
        val head = splitUnquoted(line.substring(0, colon), ';')
        val params = head.drop(1).mapNotNull { param ->
            val eq = param.indexOf('=')
            if (eq <= 0) null else param.substring(0, eq).trim().uppercase() to param.substring(eq + 1).trim().trim('"')
        }.toMap()
        return Line(head.first().trim().uppercase(), params, line.substring(colon + 1))
    }

    private fun splitUnquoted(text: String, separator: Char): List<String> {
        val parts = ArrayList<String>()
        val part = StringBuilder()
        var quoted = false
        for (c in text) {
            when {
                c == '"' -> {
                    quoted = !quoted
                    part.append(c)
                }

                c == separator && !quoted -> parts += part.toString().also { part.clear() }

                else -> part.append(c)
            }
        }
        parts += part.toString()
        return parts
    }

    private fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(c)
            }
        }
    }

    private fun unescape(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n', 'N' -> append('\n')
                    else -> append(next)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /** Appends a content line folded at 75 bytes of UTF-8, never splitting a character. */
    private fun StringBuilder.fold(line: String) {
        var used = 0
        var i = 0
        while (i < line.length) {
            val pair = line[i].isHighSurrogate() && i + 1 < line.length
            val chars = if (pair) 2 else 1
            val bytes = when {
                pair -> 4
                line[i].code < 0x80 -> 1
                line[i].code < 0x800 -> 2
                else -> 3
            }
            if (used + bytes > LINE_BYTES) {
                append("\r\n ")
                used = 1
            }
            append(line, i, i + chars)
            used += bytes
            i += chars
        }
        append("\r\n")
    }
}

private const val LINE_BYTES = 75
private const val DATE_DIGITS = 8
private const val DATE_TIME_LENGTH = 15
