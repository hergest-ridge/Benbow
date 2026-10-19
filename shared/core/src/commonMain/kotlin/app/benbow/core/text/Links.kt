package app.benbow.core.text

/*
 * Web addresses in entries (ADR-038): what counts as one when it is pasted or typed, the short form a link shows in the
 * text, and the trackers dropped as it goes in. Only http(s) is recognized in text; the link sheet also takes mailto: and
 * tel:, and nothing else is ever opened.
 */
object Links {
    private val FULL = Regex("""^https?://[^\s<>"]+$""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""^www\.[\p{L}\p{N}-]+(\.[\p{L}\p{N}-]+)+([/?#][^\s<>"]*)?$""", RegexOption.IGNORE_CASE)
    private val IN_TEXT =
        Regex("""https?://[^\s<>"]+|www\.[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)+(?:[/?#][^\s<>"]*)?""", RegexOption.IGNORE_CASE)
    private val OPENABLE = Regex("""^(https?://\S+|mailto:\S+|tel:[+\d\s()-]+)$""", RegexOption.IGNORE_CASE)
    private val TRACKERS = Regex("""^(utm_\w+|fbclid|gclid|dclid|gbraid|wbraid|yclid|ysclid|mc_eid|igshid|_hsenc|_hsmi)$""")

    /** Hosts whose `si` parameter only says who shared the link. */
    private val SHARE_ID_HOSTS = setOf("youtube.com", "youtu.be", "music.youtube.com", "open.spotify.com")

    /** Characters that end a sentence rather than an address typed or pasted in it. */
    private const val TRAILING = ".,;:!?…»\"'"
    private const val SHORT = 40

    /** The address [text] is when it is one and nothing else (spaces around allowed), cleaned; else null. */
    fun parse(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        return when {
            FULL.matches(t) -> clean(t)
            BARE.matches(t) -> clean("https://$t")
            else -> null
        }
    }

    /** Addresses inside [text] as (start, end, href), trailing punctuation left to the sentence. */
    fun find(text: String): List<Triple<Int, Int, String>> = IN_TEXT.findAll(text).mapNotNull { m ->
        val end = m.range.first + trimTrailing(m.value).length
        parse(text.substring(m.range.first, end))?.let { Triple(m.range.first, end, it) }
    }.toList()

    /** [token] without punctuation that belongs to the sentence around it (a closing bracket only if unopened). */
    fun trimTrailing(token: String): String {
        var end = token.length
        while (end > 0) {
            val c = token[end - 1]
            val unopened = c == ')' && token.substring(0, end).count { it == '(' } < token.substring(0, end).count { it == ')' }
            if (c !in TRAILING && !unopened) break
            end--
        }
        return token.substring(0, end)
    }

    /** Whether the app may hand [href] to a browser, a mail app or the phone. */
    fun isOpenable(href: String): Boolean = OPENABLE.matches(href.trim())

    /** What the link sheet's field holds as an address: a bare domain gets https://, anything unopenable is refused. */
    fun fromField(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        parse(t)?.let { return it }
        if (isOpenable(t)) return t
        val domain = Regex("""^[\p{L}\p{N}-]+(\.[\p{L}\p{N}-]+)+([/?#]\S*)?$""")
        return if (domain.matches(t)) clean("https://$t") else null
    }

    /** [href] without known trackers (`utm_*`, `fbclid`, ...); everything else, the order included, stays. */
    fun clean(href: String): String {
        val hashAt = href.indexOf('#').let { if (it < 0) href.length else it }
        val queryAt = href.indexOf('?').takeIf { it in 0 until hashAt } ?: return href
        val host = host(href)
        val kept = href.substring(queryAt + 1, hashAt).split('&').filter { param ->
            val name = param.substringBefore('=')
            param.isNotEmpty() && !TRACKERS.matches(name) && !(name == "si" && host in SHARE_ID_HOSTS)
        }
        val query = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        return href.substring(0, queryAt) + query + href.substring(hashAt)
    }

    /** The site's name in an address: `habr.com` for `https://www.habr.com/ru/...`; empty when there is none. */
    fun host(href: String): String {
        val rest = href.substringAfter("://", "")
        if (rest.isEmpty()) return ""
        return rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@').substringBefore(':')
            .lowercase().removePrefix("www.")
    }

    /** How a link with no words of its own reads in the text: `habr.com/ru/articles/845662`, cut with … when long. */
    fun display(href: String): String {
        val mail = href.removePrefix("mailto:").takeIf { it != href }
        if (mail != null) return mail
        val tel = href.removePrefix("tel:").takeIf { it != href }
        if (tel != null) return tel
        val bare = decodePercent(href.substringAfter("://", href)).removePrefix("www.").trimEnd('/')
        return if (bare.length <= SHORT) bare else bare.take(SHORT - 1).trimEnd('/', '-', '_', '.', '?', '&') + "…"
    }

    /** Percent escapes as the letters they stand for (Cyrillic paths read as words); left as is when not valid UTF-8. */
    private fun decodePercent(text: String): String {
        if ('%' !in text) return text
        val bytes = ArrayList<Byte>()
        val out = StringBuilder()
        fun flush() {
            if (bytes.isEmpty()) return
            out.append(bytes.toByteArray().decodeToString())
            bytes.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hex = if (c == '%' && i + 2 <= text.lastIndex) text.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null && hex >= 0x80) {
                bytes.add(hex.toByte())
                i += 3
                continue
            }
            flush()
            out.append(c)
            i++
        }
        flush()
        val decoded = out.toString()
        return if (Char(0xFFFD) in decoded && Char(0xFFFD) !in text) text else decoded
    }
}
