package app.benbow.core.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Sites whose own pages tell a link's card little, asked by oEmbed instead (owner, 2026-10-30): YouTube's head comes past
 * 0.7 MB of its page and its description is mostly ads, Spotify's page is a wall where it isn't sold, and every one of
 * these answers in a small JSON with the title, who made it and a picture.
 */
object Oembed {
    /**
     * A site that answers oEmbed: its [hosts] (and their subdomains), where it answers, and whether who made the thing goes
     * on the card's second line ([author]). Without, the card has no description: the owner wants none for YouTube.
     */
    class Provider internal constructor(val hosts: List<String>, internal val endpoint: String, internal val author: Boolean)

    private val providers = listOf(
        Provider(listOf("youtube.com", "youtu.be", "youtube-nocookie.com"), "https://www.youtube.com/oembed?format=json&url=", true),
        Provider(listOf("open.spotify.com"), "https://open.spotify.com/oembed?url=", false),
        Provider(listOf("vimeo.com"), "https://vimeo.com/api/oembed.json?url=", true),
        Provider(listOf("soundcloud.com"), "https://soundcloud.com/oembed?format=json&url=", true),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The site that answers for [url], or null. */
    fun providerOf(url: String): Provider? {
        val host = hostOf(url) ?: return null
        return providers.firstOrNull { p -> p.hosts.any { host == it || host.endsWith(".$it") } }
    }

    /** Where to ask [provider] about [url]. */
    fun endpoint(provider: Provider, url: String): String = provider.endpoint + percentEncoded(url)

    /** What an oEmbed answer says, as a page's head would; null when it names nothing. */
    fun meta(provider: Provider, body: String, base: String): PageMeta? {
        val answer = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        fun field(name: String) = runCatching { answer[name]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val title = field("title") ?: return null
        return PageMeta(
            title = title,
            description = field("author_name")?.takeIf { provider.author },
            site = field("provider_name"),
            image = field("thumbnail_url")?.let { PageMeta.resolve(base, it) },
        ).trimmed()
    }

    private fun hostOf(url: String): String? = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').substringBefore(':').lowercase().takeIf { it.isNotEmpty() }

    /** [text] as a query value: every byte but the unreserved ones percent-encoded, as UTF-8. */
    internal fun percentEncoded(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (c.toChar().let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._~" }) {
                append(c.toChar())
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
