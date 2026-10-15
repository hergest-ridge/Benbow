package app.benbow.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The browser's addresses (W4): what each screen shows there, and where a typed or bookmarked one leads. */
class AddressTest {
    private val id = "01a14078-c55c-7570-a888-4f20efdc0e83"

    @Test
    fun screensHaveAddresses() {
        assertEquals("", Route.Home.path())
        assertEquals("feed", Route.Feed.path())
        assertEquals("entry/$id", Route.Editor(id, "2026-10-15", readOnly = true).path())
        assertEquals("new", Route.Editor(null, "2026-10-15").path())
        assertEquals("entry/$id/comments", Route.Comments(id).path())
        assertEquals("entry/$id", Route.Media(id, "a1").path(), "the viewer shows its entry's address")
        assertEquals("server", Route.Server.path())
    }

    @Test
    fun addressesLeadBack() {
        assertEquals(Link.Tab(Route.Home), linkOf("/"))
        assertEquals(Link.Tab(Route.Calendar), linkOf("/calendar"))
        assertEquals(Link.Entry(id), linkOf("/entry/$id"))
        assertEquals(Link.Entry(id, comments = true), linkOf("/entry/$id/comments"))
        assertEquals(Link.Page(Route.Server), linkOf("server"))
        assertEquals(Link.NewEntry, linkOf("/new"))
        for (route in listOf(Route.Feed, Route.Search, Route.More, Route.Trash, Route.Export, Route.Appearance)) {
            assertEquals(route, linkOf(route.path()).let { (it as? Link.Tab)?.route ?: (it as? Link.Page)?.route }, "$route")
        }
    }

    @Test
    fun strangeAddressesLeadNowhere() {
        assertNull(linkOf("/entry/<script>"))
        assertNull(linkOf("/entry"))
        assertNull(linkOf("/nonsense"))
    }
}
