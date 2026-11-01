package app.benbow.core.text

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageMetaTest {
    @Test
    fun openGraphWinsOverTheTitleAndRelativeAddressesResolve() {
        val html = """
            <html><head>
            <meta charset="utf-8"><title>Хабр &mdash; статья</title>
            <meta property="og:title" content="Как устроены маяки: линзы Френеля">
            <meta name="description" content="Короткое &laquo;описание&raquo;">
            <meta property='og:site_name' content=Хабр>
            <meta property="og:image" content="/img/cover.png">
            <link rel="apple-touch-icon" href="icon-180.png">
            </head><body><meta property="og:title" content="not in head"></body></html>
        """.trimIndent()
        val meta = PageMeta.read(html, "https://habr.com/ru/articles/1/")
        assertEquals("Как устроены маяки: линзы Френеля", meta.title)
        assertEquals("Короткое «описание»", meta.description)
        assertEquals("Хабр", meta.site)
        assertEquals("https://habr.com/img/cover.png", meta.image)
        assertEquals("https://habr.com/ru/articles/1/icon-180.png", meta.icon)
    }

    @Test
    fun aPlainPageGivesItsTitle() {
        val meta = PageMeta.read("<title>\n  Just   a page </title>", "https://x.org")
        assertEquals("Just a page", meta.title)
        assertNull(meta.image)
        assertNull(meta.icon)
        assertEquals("windows-1251", PageMeta.charsetIn("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1251\">"))
    }

    @Test
    fun addressesResolveLikeABrowser() {
        assertEquals("https://cdn.x.org/a.png", PageMeta.resolve("https://x.org/p", "//cdn.x.org/a.png"))
        assertEquals("https://x.org/dir/a.png", PageMeta.resolve("https://x.org/dir/page?q=1", "a.png"))
        assertEquals("https://x.org/a.png", PageMeta.resolve("https://x.org", "a.png"))
        assertNull(PageMeta.resolve("https://x.org", "data:image/png;base64,AAA"))
    }

    @Test
    fun theReaderFollowsRedirectsAndFetchesThePicture() = runTest {
        val pages = mapOf(
            "https://short.ly/a" to PageResponse(301, "https://x.org/post", null, ByteArray(0)),
            "https://x.org/post" to
                PageResponse(
                    200,
                    null,
                    "text/html; charset=utf-8",
                    """<meta property="og:image" content="/p.jpg"><title>Post</title>""".encodeToByteArray(),
                ),
            "https://x.org/p.jpg" to PageResponse(200, null, "image/jpeg", byteArrayOf(1, 2, 3)),
        )
        val seen = ArrayList<String>()
        val read = PageReader.read("https://short.ly/a", { url, _, _ ->
            seen += url
            pages.getValue(url)
        }, { b, _ -> b.decodeToString() })!!
        assertEquals("https://x.org/post", read.url)
        assertEquals("Post", read.meta.title)
        assertEquals(listOf<Byte>(1, 2, 3), read.image?.toList())
        // A host the caller refuses is never asked.
        assertNull(
            PageReader.read("https://short.ly/a", { url, _, _ -> pages.getValue(url) }, { b, _ -> b.decodeToString() }, allowed = {
                "short.ly" !in it
            }),
        )
        assertEquals(3, seen.size)
    }

    @Test
    fun anAddressThatIsAPictureOrAGifSitesPageGivesThePictureWhole() = runTest {
        val gif = "GIF89a".encodeToByteArray() + byteArrayOf(0xE0.toByte(), 0x01, 0x0E, 0x01, 0, 0, 0)
        val pages = mapOf(
            "https://x.org/cat.gif" to PageResponse(200, null, "image/gif", gif),
            "https://giphy.com/gifs/cat-123" to
                PageResponse(
                    200,
                    null,
                    "text/html",
                    """<meta property="og:image" content="https://media.giphy.com/c.gif"><title>Cat</title>""".encodeToByteArray(),
                ),
            "https://media.giphy.com/c.gif" to PageResponse(200, null, "image/gif; charset=binary", gif),
            "https://x.org/post" to
                PageResponse(200, null, "text/html", """<meta property="og:image" content="https://x.org/c.gif">""".encodeToByteArray()),
            "https://x.org/c.gif" to PageResponse(200, null, "image/gif", gif),
        )
        val get = PageGet { url, _, _ -> pages.getValue(url) }
        val hints = ArrayList<PictureHint>()
        val direct = PageReader.read("https://x.org/cat.gif", get, { b, _ -> b.decodeToString() }, onPicture = { hints += it })!!
        // Told before the whole file comes, with the size its first bytes carry.
        assertEquals(listOf(Triple("image/gif", 480, 270)), hints.map { Triple(it.mime, it.width, it.height) })
        assertEquals("image/gif", direct.own?.mime)
        assertEquals(480 to 270, direct.own?.let { it.width to it.height })
        assertEquals(gif.toList(), direct.own?.bytes?.toList(), "a GIF stays as it came")
        val site = PageReader.read("https://giphy.com/gifs/cat-123", get, { b, _ -> b.decodeToString() })!!
        assertEquals("Cat", site.meta.title)
        assertEquals("image/gif", site.own?.mime)
        // Any other page with a GIF for its picture stays a page with a picture.
        val post = PageReader.read("https://x.org/post", get, { b, _ -> b.decodeToString() }, onPicture = { hints += it })!!
        assertEquals(1, hints.size, "a page with a GIF for its picture is no picture on its way")
        assertNull(post.own)
        assertEquals(gif.toList(), post.image?.toList())
    }

    @Test
    fun endlessRedirectsGiveUp() = runTest {
        val loop = PageReader.read("https://a.org", { _, _, _ ->
            PageResponse(302, "https://a.org", null, ByteArray(0))
        }, { b, _ -> b.decodeToString() })
        assertNull(loop)
    }

    /** Owner, 2026-10-30: YouTube's card showed its address, Spotify's a wall of its own; their oEmbed says what they are. */
    @Test
    fun sitesThatAnswerOembedAreAskedThere() = runTest {
        val youtube = "https://youtu.be/nyQWhoBRp1A?si=AS24NHKFAG-VtLG9"
        val spotify = "https://open.spotify.com/album/7jtq1PqAd5akkf51oSGDnP"
        val asked = ArrayList<String>()
        val pages = mapOf(
            Oembed.endpoint(Oembed.providerOf(youtube)!!, youtube) to PageResponse(
                200,
                null,
                "application/json",
                """{"title":"ДЖОН ЛЕННОН: настоящий рокер?","author_name":"Александр Пушной","provider_name":"YouTube",
                "thumbnail_url":"https://i.ytimg.com/vi/nyQWhoBRp1A/hqdefault.jpg"}""".encodeToByteArray(),
            ),
            Oembed.endpoint(Oembed.providerOf(spotify)!!, spotify) to PageResponse(
                200,
                null,
                "application/json",
                """{"title":"ÉGRÉGORE","provider_name":"Spotify","thumbnail_url":"https://image-cdn-ak.spotifycdn.com/image/ab67"}"""
                    .encodeToByteArray(),
            ),
            "https://i.ytimg.com/vi/nyQWhoBRp1A/hqdefault.jpg" to PageResponse(200, null, "image/jpeg", byteArrayOf(1, 2, 3)),
            "https://image-cdn-ak.spotifycdn.com/image/ab67" to PageResponse(200, null, "image/jpeg", byteArrayOf(4, 5)),
        )
        val get = PageGet { url, _, _ ->
            asked += url
            pages.getValue(url)
        }
        val video = PageReader.read(youtube, get, { b, _ -> b.decodeToString() })!!
        assertEquals("ДЖОН ЛЕННОН: настоящий рокер?", video.meta.title)
        assertEquals("Александр Пушной", video.meta.description, "the channel on the second line, not its ads")
        assertEquals("YouTube", video.meta.site)
        assertEquals(3, video.image?.size)
        assertEquals(
            "https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fyoutu.be%2FnyQWhoBRp1A%3Fsi%3DAS24NHKFAG-VtLG9",
            asked.first(),
        )
        val album = PageReader.read(spotify, get, { b, _ -> b.decodeToString() })!!
        assertEquals("ÉGRÉGORE", album.meta.title)
        assertNull(album.meta.description)
        assertEquals(2, album.image?.size)
    }

    /** Its oEmbed silent, such a site's page is read, but its description (YouTube's ads) is left out. */
    @Test
    fun aSiteWhoseOembedDoesntAnswerIsReadWithoutItsDescription() = runTest {
        val url = "https://www.youtube.com/watch?v=nyQWhoBRp1A"
        val page = """<html><head><title>T - YouTube</title><meta property="og:title" content="ДЖОН ЛЕННОН">
            <meta property="og:description" content="Зимние шины"></head>""".encodeToByteArray()
        val get = PageGet { asked, _, _ ->
            if (asked == url) PageResponse(200, null, "text/html; charset=utf-8", page) else PageResponse(500, null, null, ByteArray(0))
        }
        val read = PageReader.read(url, get, { b, _ -> b.decodeToString() })!!
        assertEquals("ДЖОН ЛЕННОН", read.meta.title)
        assertNull(read.meta.description)
    }

    /** A page is read up to the end of its head, wherever the chunks part (YouTube's comes some 0.8 MB in). */
    @Test
    fun theHeadsEndIsFoundAcrossChunks() {
        fun chunk(text: String) = text.encodeToByteArray()
        val split = HeadEnd()
        assertEquals(false, split.reached(chunk("<script>…</script></HE"), chunk("<script>…</script></HE").size))
        assertEquals(true, split.reached(chunk("AD><body>"), chunk("AD><body>").size), "the mark parted between two chunks")
        assertEquals(false, HeadEnd().reached(chunk("AD><body>"), chunk("AD><body>").size), "half a mark is none")
        assertEquals(true, HeadEnd().reached(chunk("<title>x</title></head>"), chunk("<title>x</title></head>").size))
        assertEquals(null, Oembed.providerOf("https://example.com/watch?v=1"))
        assertEquals(true, Oembed.providerOf("https://m.youtube.com/watch?v=1") != null)
    }
}
