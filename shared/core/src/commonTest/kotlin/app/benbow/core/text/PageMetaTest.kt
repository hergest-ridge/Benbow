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
            PageReader.read("https://short.ly/a", { url, _, _ -> pages.getValue(url) }, { b, _ -> b.decodeToString() }) {
                "short.ly" !in
                    it
            },
        )
        assertEquals(3, seen.size)
    }

    @Test
    fun endlessRedirectsGiveUp() = runTest {
        val loop = PageReader.read("https://a.org", { _, _, _ ->
            PageResponse(302, "https://a.org", null, ByteArray(0))
        }, { b, _ -> b.decodeToString() })
        assertNull(loop)
    }
}
