package app.benbow.server

import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.offsite.OffsiteCopy
import app.benbow.server.offsite.OffsiteFolder
import app.benbow.server.offsite.OffsiteKey
import app.benbow.server.offsite.OffsiteSetup
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import app.benbow.server.storage.writeMark
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Turning the copy outside the house on and off on the admin page (ADR-043, E4): the folder, the code, the passphrase. */
class OffsiteSetupTest {
    private val data = Files.createTempDirectory("benbow-offsite-setup")
    private val db = Database.open(data, "test")
    private val blobs = BlobStore(db, data.resolve("media"))
    private val cloud = Files.createTempDirectory("benbow-cloud")
    private val copy = OffsiteCopy(db, data, null, blobs)
    private var clock = 1_792_000_000_000L
    private var passes = 0
    private val setup =
        OffsiteSetup(copy, data, db.storeId, { listOf("данные" to data) }, TestDisks(), { passes++ }, { clock }, workFactor = 10)

    init {
        ServerConfig.load(data)
    }

    private fun tail(key: OffsiteKey) = key.secret.takeLast(OffsiteSetup.TAIL)

    private fun begin(path: String, passphrase: Boolean = false): OffsiteSetup.Pending {
        assertIs<OffsiteSetup.Verdict.Fine>(setup.begin(path, passphrase))
        return assertNotNull(setup.pending)
    }

    @Test
    fun onlyAnEmptyOrOwnLocalFolderApartFromHomeWillDo() {
        fun refused(text: String) = assertIs<OffsiteSetup.Verdict.Refused>(setup.check(text)).reason
        assertTrue("впишите" in refused(" "))
        assertTrue("сетевые" in refused("\\\\nas\\backup"))
        assertTrue("полный путь" in refused("Benbow"))
        assertTrue("внутри" in refused(data.resolve("copy").toString()))
        assertTrue("содержать" in refused(data.parent.toString()) || "внутри" in refused(data.parent.toString()))
        val busy = cloud.resolve("busy").createDirectories().also { it.resolve("отпуск.jpg").writeText("x") }
        assertTrue("уже что-то лежит" in refused(busy.toString()))
        val foreign = cloud.resolve("foreign").createDirectories().also { writeMark(it, OffsiteFolder.MARK, "x", "another-store") }
        assertTrue("другому хранилищу" in refused(foreign.toString()))
        assertIs<OffsiteSetup.Verdict.Fine>(setup.check(cloud.resolve("Benbow").toString()))
        assertFalse(cloud.resolve("Benbow").exists(), "a look makes nothing")
    }

    @Test
    fun theCodeTypedBackTurnsItOn() {
        val path = cloud.resolve("Benbow")
        val step = begin(path.toString())
        assertTrue(step.fresh)
        assertFalse(copy.keyFile.exists(), "the key is kept only once the copy is on")
        assertIs<OffsiteSetup.Outcome.Refused>(setup.confirmCode(step.id, "QQQQQQ"))
        assertNull(copy.root)
        val typed = tail(step.key).lowercase().chunked(3).joinToString(" ")
        assertEquals(OffsiteSetup.Outcome.Done, setup.confirmCode(step.id, typed))
        assertEquals(step.key, copy.key())
        assertEquals(path, copy.root)
        assertEquals(path.toString().replace('\\', '/'), ServerConfig.load(data).backupOffsite.toString().replace('\\', '/'))
        assertTrue("recipient=${step.key.recipient}" in path.resolve(OffsiteFolder.MARK).readText())
        assertEquals(1, passes, "the first copy starts at once")
        assertFalse(setup.hasPassphrase())
    }

    @Test
    fun aPassphraseSealsTheKeyInTheCopyAndIsCheckedLater() {
        val path = cloud.resolve("Benbow")
        val step = begin(path.toString(), passphrase = true)
        assertTrue(
            "не совпадают" in
                assertIs<OffsiteSetup.Outcome.Refused>(setup.confirmPassphrase(step.id, "длинный пароль 1", "длинный пароль 2")).reason,
        )
        assertTrue("короче" in assertIs<OffsiteSetup.Outcome.Refused>(setup.confirmPassphrase(step.id, "short", "short")).reason)
        val passphrase = "Ёжик в тумане 1975"
        assertEquals(OffsiteSetup.Outcome.Done, setup.confirmPassphrase(step.id, passphrase, passphrase))
        assertEquals(step.key, OffsiteKey.unseal(path.resolve(OffsiteSetup.KEY_AGE).readBytes(), passphrase))
        assertTrue(setup.hasPassphrase())
        assertEquals(clock, copy.passphraseAt())
        clock += 1_000
        assertEquals(false, setup.checkPassphrase("не тот пароль"))
        assertEquals(clock - 1_000, copy.passphraseAt(), "a wrong one doesn't count")
        assertEquals(true, setup.checkPassphrase(passphrase))
        assertEquals(clock, copy.passphraseAt())

        assertEquals(OffsiteSetup.Outcome.Done, setup.setPassphrase("другой пароль 2026", "другой пароль 2026"))
        assertEquals(true, setup.checkPassphrase("другой пароль 2026"))
        assertEquals(false, setup.checkPassphrase(passphrase))
        assertFalse(path.resolve("${OffsiteSetup.KEY_AGE}.part").exists())
    }

    @Test
    fun turnedOffItKeepsItsFolderAndKeyAndComesBackToThem() {
        val path = cloud.resolve("Benbow")
        val first = begin(path.toString())
        setup.confirmCode(first.id, tail(first.key))
        path.resolve("db").createDirectories().resolve("x.age").writeText("as if a pass had been")
        setup.turnOff()
        assertNull(copy.root)
        assertNull(ServerConfig.load(data).backupOffsite)
        assertTrue(path.resolve("db").resolve("x.age").exists() && copy.keyFile.exists())

        val again = begin(path.toString())
        assertFalse(again.fresh, "the same key: the code written down stays good")
        assertEquals(first.key, again.key)
        assertEquals(OffsiteSetup.Outcome.Done, setup.confirmCode(again.id, tail(again.key)))
        assertEquals(path, copy.root)
    }

    @Test
    fun aStepLeftHalfAnHourIsForgottenAndScryptTheServerCantAffordIsRefused() {
        val step = begin(cloud.resolve("Benbow").toString())
        clock += OffsiteSetup.PENDING_MILLIS
        assertTrue("устарел" in assertIs<OffsiteSetup.Outcome.Refused>(setup.confirmCode(step.id, tail(step.key))).reason)
        val heavy = OffsiteSetup(copy, data, db.storeId, { emptyList() }, TestDisks(), {}, { clock }, workFactor = 30)
        assertIs<OffsiteSetup.Verdict.Fine>(heavy.begin(cloud.resolve("Heavy").toString(), passphrase = true))
        val pending = assertNotNull(heavy.pending)
        assertTrue(
            "памяти" in
                assertIs<OffsiteSetup.Outcome.Refused>(
                    heavy.confirmPassphrase(pending.id, "достаточно длинный", "достаточно длинный"),
                ).reason,
        )
        assertNull(copy.root)
    }

    @Test
    fun theAdminPageTurnsItOnWithTheCodeAndOnlyThisComputerSeesTheCode() {
        var loopback = true
        val ctx = ServerContext(
            db,
            Devices(db),
            PairingCodes(),
            "AA:BB",
            data,
            8765,
            isLoopback = { loopback },
            blobs = blobs,
            offsite = copy,
            offsiteSetup = setup,
        )
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { followRedirects = false }
            assertTrue("Копия вне дома" in client.get("/").bodyAsText())
            val path = cloud.resolve("Benbow")
            val page = client.submitForm(
                "/admin/offsite/begin",
                parameters {
                    append("offsite-path", path.toString())
                    append("way", "code")
                },
            ).bodyAsText()
            val step = Regex("""name="step" value="([^"]+)"""").find(page)!!.groupValues[1]
            val key = setup.pending!!.key
            assertTrue(key.grouped() in page, "the code is shown to write down")
            val file = client.get("/admin/offsite/code.txt?step=$step").bodyAsText()
            assertTrue(file.lines().last { it.isNotBlank() } == key.secret && file.startsWith("# Benbow"), file)
            assertTrue("<svg" in client.get("/admin/offsite/code?step=$step").bodyAsText(), "a QR code to print")

            val done = client.submitForm(
                "/admin/offsite/confirm",
                parameters {
                    append("step", step)
                    append("way", "code")
                    append("tail", tail(key))
                },
            )
            assertEquals(HttpStatusCode.Found, done.status)
            assertEquals(path, copy.root)
            val on = client.get("/").bodyAsText()
            assertTrue("кодом восстановления" in on && "Показать код" in on, on.substringAfter("Копия вне дома").take(600))
            assertTrue("Папка" in client.get("/?part=offsite").bodyAsText())

            loopback = false
            assertEquals(HttpStatusCode.Forbidden, client.get("/admin/offsite/code").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/admin/offsite/code.txt").status)
            loopback = true
            assertEquals(HttpStatusCode.Found, client.submitForm("/admin/offsite/off", parameters { }).status)
            assertNull(copy.root)
        }
    }
}
