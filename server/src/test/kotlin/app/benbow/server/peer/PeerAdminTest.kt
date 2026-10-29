package app.benbow.server.peer

import app.benbow.server.api.FolderBrowser
import app.benbow.server.api.FolderList
import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The friends' section of the admin page: only for the server PC, cards checked, the password never shown. */
class PeerAdminTest {
    private val dir = Files.createTempDirectory("benbow-peer-admin")

    private fun withPage(loopback: Boolean = true, block: suspend (PeerLink, HttpClient) -> Unit) {
        val db = Database.inMemory()
        val peers = PeerLink(
            PeerDatabase.inMemory(),
            PeerIdentity.generate(),
            connect = { error("no network in this test") },
            peerDir = dir.resolve("peer"),
        )
        val ctx = ServerContext(db, Devices(db), PairingCodes(), "AA:BB", null, 8765, isLoopback = { loopback }, peers = peers)
        testApplication {
            application { benbowModule(ctx) }
            block(peers, createClient { followRedirects = false })
        }
        peers.close()
        db.close()
    }

    @Test
    fun theLanSeesNoCardAndCanChangeNothing() = withPage(loopback = false) { peers, client ->
        assertFalse(peers.identity.card.encode() in client.get("/").bodyAsText())
        val friend = PeerIdentity.generate().card.encode()
        val post = client.submitForm(
            "/admin/peers/contacts",
            parameters {
                append("name", "Аня")
                append("card", friend)
            },
        )
        assertEquals(HttpStatusCode.Forbidden, post.status)
        assertTrue(peers.db.contacts().isEmpty())
    }

    @Test
    fun cardsContactsNotesAndTheMailbox() = withPage { peers, client ->
        val page = client.get("/").bodyAsText()
        assertTrue(peers.identity.card.encode() in page && peers.identity.card.fingerprint in page)
        assertTrue("Ящик не настроен" in page)

        val friend = PeerIdentity.generate().card
        suspend fun add(card: String) = client.submitForm(
            "/admin/peers/contacts",
            parameters {
                append("name", "Аня")
                append("card", card)
            },
        )
        assertTrue("обрезана" in add(friend.encode().dropLast(6)).bodyAsText())
        assertTrue("этого же сервера" in add(peers.identity.card.encode()).bodyAsText())
        assertEquals(HttpStatusCode.Found, add(friend.encode()).status)
        assertEquals(listOf("Аня"), peers.db.contacts().map { it.name })

        val note = client.submitForm("/admin/peers/contacts/${friend.id}/note", parameters { append("text", "Проверка связи") })
        assertEquals(HttpStatusCode.Found, note.status)
        val withNote = client.get("/").bodyAsText()
        assertTrue("Проверка связи" in withNote && "ждёт отправки" in withNote && friend.fingerprint in withNote)

        suspend fun mail(password: String) = client.submitForm(
            "/admin/peers/mail",
            parameters {
                append("host", "imap.example.test")
                append("port", "993")
                append("user", "box")
                append("password", password)
            },
        )
        assertEquals(HttpStatusCode.Found, mail("app-password-1").status)
        assertFalse("app-password-1" in client.get("/").bodyAsText(), "the password is never shown")
        mail("") // the form sent without a password keeps the stored one
        assertEquals("app-password-1", peers.db.mailSettings()?.password)

        client.submitForm("/admin/peers/contacts/${friend.id}/remove", parameters {})
        assertTrue(peers.db.contacts().isEmpty())
    }

    @Test
    fun aFileIsSealedAsItArrivesAndWaitsForTheMailbox() = withPage { peers, client ->
        val friend = PeerIdentity.generate().card
        peers.db.addContact("Аня", friend, 0)
        assertTrue("Отправить файл" !in client.get("/").bodyAsText(), "no sending before the mailbox is set")
        peers.db.saveMailSettings(MailSettings("imap.example.invalid", 993, "box", "secret"))

        val content = "секретное содержимое".toByteArray()
        val upload = client.submitFormWithBinaryData(
            "/admin/peers/contacts/${friend.id}/file",
            formData {
                append(
                    "file",
                    content,
                    Headers.build {
                        append(HttpHeaders.ContentType, "text/plain")
                        append(HttpHeaders.ContentDisposition, "filename=\"заметка.txt\"")
                    },
                )
            },
        )
        assertEquals(HttpStatusCode.Found, upload.status)
        val sent = peers.db.filesOut(friend.id, 10).single()
        assertEquals("заметка.txt", sent.name)
        assertEquals(content.size.toLong(), sent.size)
        assertEquals(VIA_MAIL, sent.via)
        val sealed = dir.resolve("peer/outgoing/${sent.id}.sealed")
        assertTrue(Files.exists(sealed))
        val needle = "секретное".toByteArray().toList()
        assertFalse(Files.readAllBytes(sealed).toList().windowed(needle.size).any { it == needle }, "no plain copy")
        val page = client.get("/").bodyAsText()
        assertTrue("заметка.txt" in page && "готовится к отправке" in page)
    }

    @Test
    fun filesGoTheWayChosenAndTheSecondMailboxKeepsItsPassword() = withPage { peers, client ->
        assertEquals(VIA_MAIL, peers.db.filesVia())
        assertTrue("Через отдельный ящик для файлов" in client.get("/").bodyAsText())
        assertEquals(HttpStatusCode.Found, client.submitForm("/admin/peers/files", parameters { append("via", VIA_PARTS_MAIL) }).status)
        assertEquals(VIA_PARTS_MAIL, peers.db.filesVia())
        assertEquals(HttpStatusCode.BadRequest, client.submitForm("/admin/peers/files", parameters { append("via", "pigeon") }).status)

        suspend fun save(password: String) = client.submitForm(
            "/admin/peers/parts-mail",
            parameters {
                append("host", "imap.gmail.com")
                append("port", "993")
                append("user", "files@example.com")
                append("password", password)
            },
        )
        assertEquals(HttpStatusCode.Found, save("files-password").status)
        save("")
        assertEquals("files-password", peers.db.partsMailSettings()?.password)
        val page = client.get("/").bodyAsText()
        assertFalse("files-password" in page, "the password is never shown")
        assertTrue("files@example.com" in page)
    }

    @Test
    fun theDriveFolderOfOlderServersMustBeThere() = withPage { _, client ->
        val missing = client.submitForm("/admin/peers/drive", parameters { append("path", dir.resolve("missing").toString()) })
        assertTrue("нет" in missing.bodyAsText())
        val drive = Files.createDirectories(dir.resolve("My Drive"))
        assertEquals(HttpStatusCode.Found, client.submitForm("/admin/peers/drive", parameters { append("path", "\"$drive\"") }).status)
    }

    @Test
    fun thePageRunsOnlyItsOwnFilesAndRefreshesItsFriendsPart() = withPage { peers, client ->
        val friend = PeerIdentity.generate().card
        peers.db.addContact("Аня", friend, 0)
        val page = client.get("/")
        val csp = page.headers["Content-Security-Policy"].orEmpty()
        assertTrue("script-src 'self'" in csp && "default-src 'none'" in csp, csp)
        val html = page.bodyAsText()
        assertFalse(Regex("""\son[a-z]+="""").containsMatchIn(html), "no inline handlers")
        assertFalse(Regex("""<script>""").containsMatchIn(html), "no inline scripts")
        assertTrue("data-live=\"friends\"" in html)
        assertEquals(HttpStatusCode.OK, client.get("/admin/assets/admin.js").status)
        val icon = client.get("/admin/assets/icon.svg")
        assertTrue(icon.status == HttpStatusCode.OK && "<svg" in icon.bodyAsText(), "the app's icon")
        assertTrue("rel=\"icon\" href=\"/admin/assets/icon.svg\"" in html && "class=\"logo\" src=\"/admin/assets/icon.svg\"" in html)
        assertEquals(HttpStatusCode.NotFound, client.get("/admin/assets/secret.txt").status)

        // A friend's note can't become markup on this page.
        client.submitForm("/admin/peers/contacts/${friend.id}/note", parameters { append("text", "<img src=x onerror=alert(1)>") })
        val part = client.get("/?part=friends").bodyAsText()
        assertTrue("&lt;img src=x onerror=alert(1)&gt;" in part && "<img" !in part)
        assertTrue("Аня" in part && "<html" !in part, "only the section")

        val done = client.get("/?done=note").bodyAsText()
        assertTrue("Сообщение отправлено" in done)
        assertFalse("<script>alert" in client.get("/?done=<script>alert(1)</script>").bodyAsText(), "messages come from a fixed list")
    }

    @Test
    fun theLanGetsNoLivePart() = withPage(loopback = false) { _, client ->
        assertEquals(HttpStatusCode.Forbidden, client.get("/?part=friends").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/admin/folders").status, "the LAN sees no folders of this PC")
    }

    @Test
    fun eachSectionIsATabAndTheLinkHasItsOwn() = withPage { _, client ->
        val html = client.get("/").bodyAsText()
        for (id in listOf("overview", "devices", "backups", "friends", "link")) {
            assertTrue("<section id=\"$id\" class=\"page\"" in html && "href=\"#$id\"" in html, id)
        }
        assertTrue("id=\"folder-picker\"" in html && "data-pick-folder=\"path\"" in html)
        val link = client.get("/?part=link").bodyAsText()
        assertTrue("Почтовый ящик" in link && "Google Диск" in link && "<section" !in link)
    }

    @Test
    fun theFolderPickerListsOnlyVisibleFoldersAndKnowsADriveMirror() = withPage { _, client ->
        val root = Files.createDirectories(dir.resolve("pick"))
        for (name in listOf("Бета", "альфа", ".tmp.drivedownload", "\$RECYCLE.BIN")) Files.createDirectories(root.resolve(name))
        Files.createFile(root.resolve("file.txt"))
        val list = Json.decodeFromString<FolderList>(
            client.get("/admin/folders?path=${java.net.URLEncoder.encode(root.toString(), "UTF-8")}").bodyAsText(),
        )
        assertEquals(root.toString(), list.path)
        assertEquals(listOf("альфа", "Бета"), list.folders.map { it.name }, "no files, no hidden or system folders")
        assertEquals(root.parent.toString(), list.parent)
        assertTrue(FolderBrowser.mirrored(root), "Drive keeps .tmp.drivedownload in the folder it mirrors")
        assertTrue(
            FolderBrowser.looksLikeDrive("Google Shared") && FolderBrowser.looksLikeDrive("Мой диск") &&
                !FolderBrowser.looksLikeDrive("Documents"),
        )
        assertTrue(listOf("OneDrive", "PenTabletDriver").none(FolderBrowser::looksLikeDrive) && FolderBrowser.looksLikeDrive("My Drive"))
        val computer = Json.decodeFromString<FolderList>(client.get("/admin/folders").bodyAsText())
        assertTrue(computer.path == null && computer.disks.isNotEmpty())
        val missing = Json.decodeFromString<FolderList>(client.get("/admin/folders?path=Z:%5Cnowhere").bodyAsText())
        assertTrue(missing.error != null && missing.path == null)
    }
}
