package app.benbow.core.text

/** What a page says about itself (ADR-038): read from its `<head>`, never by running it. Addresses are absolute. */
data class PageMeta(
    val title: String? = null,
    val description: String? = null,
    val site: String? = null,
    val image: String? = null,
    val icon: String? = null,
) {
    /** Within the lengths a card keeps (an oEmbed answer may name things at any length). */
    internal fun trimmed(): PageMeta =
        copy(title = title?.cut(TITLE_MAX), description = description?.cut(DESCRIPTION_MAX), site = site?.cut(TITLE_MAX))

    companion object {
        private const val TITLE_MAX = 200
        private const val DESCRIPTION_MAX = 300

        private val TAG = Regex("""<(meta|link)\b([^>]*)>""", RegexOption.IGNORE_CASE)
        private val TITLE = Regex("""<title\b[^>]*>(.*?)</title\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val ATTR = Regex("""([a-zA-Z_:.-]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))""")
        private val CHARSET = Regex("""<meta\b[^>]*charset\s*=\s*["']?\s*([a-zA-Z0-9_.:-]+)""", RegexOption.IGNORE_CASE)
        private val SPACES = Regex("""\s+""")

        /** The page's head as [html] gives it; [base] is the page's final address (relative addresses resolve against it). */
        fun read(html: String, base: String): PageMeta {
            val head = html.substringBefore("</head", html).substringBefore("<body", html)
            val meta = HashMap<String, String>()
            val links = ArrayList<Pair<String, String>>() // rel to href
            for (tag in TAG.findAll(head)) {
                val attrs = ATTR.findAll(tag.groupValues[2]).associate { m ->
                    m.groupValues[1].lowercase() to (m.groups[3]?.value ?: m.groups[4]?.value ?: m.groupValues[5])
                }
                if (tag.groupValues[1].equals("meta", ignoreCase = true)) {
                    val name = (attrs["property"] ?: attrs["name"] ?: attrs["itemprop"])?.lowercase() ?: continue
                    val content = attrs["content"] ?: continue
                    if (name !in meta) meta[name] = content
                } else {
                    val rel = attrs["rel"]?.lowercase() ?: continue
                    val href = attrs["href"] ?: continue
                    links += rel to href
                }
            }
            fun first(vararg names: String) = names.firstNotNullOfOrNull { meta[it]?.let(::text)?.takeIf(String::isNotEmpty) }
            val ownTitle = TITLE.find(head)?.groupValues?.get(1)?.let(::text)?.takeIf { it.isNotEmpty() }
            val image = first("og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src", "image")
                ?: links.firstOrNull { it.first == "image_src" }?.second
            val icon = listOf("apple-touch-icon", "apple-touch-icon-precomposed", "icon", "shortcut icon")
                .firstNotNullOfOrNull { rel -> links.firstOrNull { it.first == rel }?.second }
            return PageMeta(
                title = (first("og:title", "twitter:title") ?: ownTitle)?.cut(TITLE_MAX),
                description = first("og:description", "twitter:description", "description")?.cut(DESCRIPTION_MAX),
                site = first("og:site_name", "application-name")?.cut(TITLE_MAX),
                image = image?.let { resolve(base, text(it)) },
                icon = icon?.let { resolve(base, text(it)) },
            )
        }

        /** The charset a page names in a `<meta>` near its top, or null. */
        fun charsetIn(head: String): String? = CHARSET.find(head)?.groupValues?.get(1)

        /** [href] relative to [base] as an absolute http(s) address, or null when it can't be one. */
        fun resolve(base: String, href: String): String? {
            val h = href.trim()
            if (h.isEmpty() || h.startsWith("data:", ignoreCase = true) || h.startsWith("javascript:", ignoreCase = true)) return null
            if (h.startsWith("http://", ignoreCase = true) || h.startsWith("https://", ignoreCase = true)) return h
            val scheme = base.substringBefore("://", "")
            if (scheme.isEmpty()) return null
            if (h.startsWith("//")) return "$scheme:$h"
            val origin = scheme + "://" + base.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            if (h.startsWith("/")) return origin + h
            val path = base.substringAfter("://").substringBefore('?').substringBefore('#').substringAfter('/', "")
            val dir = path.substringBeforeLast('/', "")
            return "$origin/" + (if (dir.isEmpty()) "" else "$dir/") + h
        }

        private fun String.cut(max: Int): String = if (length <= max) this else take(max - 1).trimEnd() + "…"

        /** Markup out, entities decoded, runs of spaces as one. */
        private fun text(raw: String): String = decodeEntities(raw.replace(Regex("<[^>]*>"), " ")).replace(SPACES, " ").trim()

        private val NAMED = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–",
            "laquo" to "«", "raquo" to "»", "hellip" to "…", "bdquo" to "„", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘",
            "rsquo" to "’", "copy" to "©", "reg" to "®", "trade" to "™", "middot" to "·", "bull" to "•", "times" to "×",
        )

        private val ENTITY = Regex("""&(#[xX][0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")

        internal fun decodeEntities(text: String): String = ENTITY.replace(text) { m ->
            val name = m.groupValues[1]
            val code = when {
                name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)
                name.startsWith("#") -> name.drop(1).toIntOrNull()
                else -> null
            }
            when {
                code != null && code in 1..0x10FFFF && code !in 0xD800..0xDFFF -> codePointString(code)
                code != null -> m.value
                else -> NAMED[name] ?: NAMED[name.lowercase()] ?: m.value
            }
        }

        private fun codePointString(code: Int): String = if (code < 0x10000) {
            Char(code).toString()
        } else {
            val v = code - 0x10000
            charArrayOf(Char(0xD800 + (v shr 10)), Char(0xDC00 + (v and 0x3FF))).concatToString()
        }
    }
}
