package app.benbow.server.peer

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
    fun aFileGoesSealedIntoTheDriveFolder() = withPage { peers, client ->
        val friend = PeerIdentity.generate().card
        peers.db.addContact("Аня", friend, 0)
        assertTrue("Отправить файл" !in client.get("/").bodyAsText(), "no sending before the folder is set")
        assertTrue(
            "нет" in client.submitForm(
                "/admin/peers/drive",
                parameters {
                    append("path", dir.resolve("missing").toString())
                },
            ).bodyAsText(),
        )
        val drive = Files.createDirectories(dir.resolve("My Drive"))
        assertEquals(HttpStatusCode.Found, client.submitForm("/admin/peers/drive", parameters { append("path", "\"$drive\"") }).status)

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
        val body = DriveFolder(drive).bodyFor(friend, sent.id)
        assertTrue(Files.exists(body))
        assertFalse(
            "секретное".toByteArray().let { needle ->
                Files.readAllBytes(body).toList().windowed(needle.size).any {
                    it ==
                        needle.toList()
                }
            },
        )
        val page = client.get("/").bodyAsText()
        assertTrue("заметка.txt" in page && "в папке Диска" in page)
    }
}
