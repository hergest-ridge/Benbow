package app.benbow.server

import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.setup.HostSurvey
import app.benbow.server.setup.NetworkAdvice
import app.benbow.server.setup.PlaceAdvice
import app.benbow.server.setup.PlaceAdvice.State
import app.benbow.server.setup.Survey
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Где хранить» (ADR-042, I1): what is suggested on which disks, the home network's checks, the survey and the page. */
class PlacesTest {
    private val gb = 1_073_741_824L

    private fun volume(letter: String, disk: Int?, free: Long, size: Long = 1_000 * gb, fs: String = "NTFS", type: String = "Fixed") =
        Survey.Volume(letter, "label $letter", fs, type, size, free, disk)

    private fun disk(number: Int, media: String, bus: String = "SATA", health: String = "Healthy") =
        Survey.PhysicalDisk(number, "Disk $number", media, bus, 1_000 * gb, health)

    /** The owner's computer as Windows told it on 2026-10-22. */
    private val owners = Survey(
        system = "C",
        volumes = listOf(
            volume("E", 1, 9 * gb),
            volume("F", 2, 68 * gb),
            volume("C", 3, 21 * gb),
            volume("H", 0, 3_130 * gb, size = 5_589 * gb),
            volume("D", 4, 169 * gb, size = 1_863 * gb),
        ),
        disks = listOf(disk(2, "HDD"), disk(0, "HDD"), disk(3, "SSD", "NVMe"), disk(1, "HDD"), disk(4, "SSD", "NVMe")),
    )

    private val nothingTaken: (String) -> Boolean = { false }

    @Test
    fun aNewServerOnTheOwnersDisksGetsTheMirrorOnTheBigHddAndTheMediaOnTheSsd() {
        val advice = PlaceAdvice.advise(owners, "C:\\ProgramData\\Benbow", "C:\\ProgramData\\Benbow\\media", 0, null, nothingTaken)
        assertEquals(State.Good, advice.database.state)
        assertEquals(State.Problem, advice.mirror.state)
        assertEquals("H:\\Benbow-mirror", advice.mirror.better?.path, "another physical disk, the most room")
        assertEquals(State.Note, advice.media.state, advice.media.says)
        assertEquals("D:\\Benbow-media", advice.media.better?.path, "off the system disk, an SSD, not the mirror's disk")
        val c = advice.disks.single { it.volume.letter == "C" }
        assertTrue(c.system)
        assertEquals(listOf("база", "медиафайлы"), c.holds)
    }

    @Test
    fun theOwnersServerNowHearsThatItsMirrorFillsFirst() {
        val advice = PlaceAdvice.advise(
            owners,
            "C:\\ProgramData\\Benbow",
            "D:\\Benbow-media",
            120 * gb,
            "F:\\[BACKUP]\\Benbow",
            nothingTaken,
        )
        assertEquals(State.Good, advice.media.state)
        assertNull(advice.media.better, "media off the system disk stay where they are")
        assertEquals(State.Note, advice.mirror.state, advice.mirror.says)
        assertTrue("заполнится раньше" in advice.mirror.says)
        assertEquals("H:\\Benbow-mirror", advice.mirror.better?.path)
        assertEquals(listOf("второй диск"), advice.disks.single { it.volume.letter == "F" }.holds)
    }

    @Test
    fun twoDisksGiveTheMirrorTheSecondOneAndKeepTheMediaWithTheSystem() {
        val survey = Survey("C", listOf(volume("C", 0, 300 * gb), volume("D", 1, 900 * gb)), listOf(disk(0, "SSD"), disk(1, "HDD")))
        val advice = PlaceAdvice.advise(survey, "C:\\ProgramData\\Benbow", "C:\\ProgramData\\Benbow\\media", 10 * gb, null, nothingTaken)
        assertEquals("D:\\Benbow-mirror", advice.mirror.better?.path)
        assertEquals(State.Good, advice.media.state, "the safety copy matters more than the media leaving the system disk")
        assertNull(advice.media.better)
    }

    @Test
    fun oneDiskAsksForAnExternalOne() {
        val survey = Survey("C", listOf(volume("C", 0, 300 * gb), volume("D", 0, 400 * gb)), listOf(disk(0, "SSD")))
        val advice = PlaceAdvice.advise(survey, "C:\\ProgramData\\Benbow", "C:\\ProgramData\\Benbow\\media", 10 * gb, null, nothingTaken)
        assertEquals(State.Problem, advice.mirror.state)
        assertNull(advice.mirror.better, "another partition of the same disk is no second disk")
        assertTrue("подключите внешний" in advice.mirror.says)
    }

    @Test
    fun aMirrorOnTheMediasPhysicalDiskIsToldAndMovedElsewhere() {
        val survey = Survey(
            "C",
            listOf(volume("C", 0, 100 * gb), volume("D", 1, 400 * gb), volume("E", 1, 400 * gb), volume("G", 2, 500 * gb)),
            listOf(disk(0, "SSD"), disk(1, "HDD"), disk(2, "HDD")),
        )
        val advice = PlaceAdvice.advise(survey, "C:\\Benbow", "D:\\Benbow-media", 50 * gb, "E:\\Benbow-mirror", nothingTaken)
        assertEquals(State.Note, advice.mirror.state)
        assertTrue("тот же физический диск" in advice.mirror.says)
        assertEquals("G:\\Benbow-mirror", advice.mirror.better?.path)
    }

    @Test
    fun anUnhealthyDiskIsSaidAndNeverSuggested() {
        val survey = Survey(
            "C",
            listOf(volume("C", 0, 100 * gb), volume("D", 1, 900 * gb), volume("E", 2, 500 * gb)),
            listOf(disk(0, "SSD"), disk(1, "HDD", health = "Warning"), disk(2, "HDD")),
        )
        val advice = PlaceAdvice.advise(survey, "C:\\Benbow", "C:\\Benbow\\media", gb, "D:\\Benbow-mirror", nothingTaken)
        assertEquals(State.Problem, advice.mirror.state)
        assertTrue("неисправным" in advice.mirror.says)
        assertEquals("E:\\Benbow-mirror", advice.mirror.better?.path)
    }

    @Test
    fun mediaNeverGoToAUsbDiskButTheMirrorMay() {
        val survey = Survey(
            "C",
            listOf(volume("C", 0, 100 * gb), volume("U", 1, 900 * gb)),
            listOf(disk(0, "SSD"), disk(1, "HDD", bus = "USB")),
        )
        val fresh = PlaceAdvice.advise(survey, "C:\\Benbow", "C:\\Benbow\\media", gb, null, nothingTaken)
        assertEquals("U:\\Benbow-mirror", fresh.mirror.better?.path)
        assertNull(fresh.media.better)
        val onUsb = PlaceAdvice.advise(survey, "C:\\Benbow", "U:\\Benbow-media", gb, null, nothingTaken)
        assertEquals(State.Problem, onUsb.media.state)
        assertTrue("съёмном" in onUsb.media.says)
    }

    @Test
    fun aFolderThatHoldsSomethingIsNotSuggested() {
        val taken = setOf("H:\\Benbow-mirror", "H:\\Benbow-mirror-2")
        val advice = PlaceAdvice.advise(owners, "C:\\ProgramData\\Benbow", "C:\\ProgramData\\Benbow\\media", 0, null) { it in taken }
        assertEquals("H:\\Benbow-mirror-3", advice.mirror.better?.path)
    }

    private fun network(category: String, rules: List<Survey.FirewallRule>) = Survey(
        networks = listOf(
            Survey.NetworkProfile("Неопознанная сеть", "vEthernet (WSLCore)", "Public", listOf("172.18.128.1")),
            Survey.NetworkProfile("Сеть 5", "Ethernet 4", category, listOf("192.168.0.102")),
        ),
        rules = rules,
    )

    private val rule = Survey.FirewallRule("True", "Allow", "Inbound", "Private", listOf("8765"), "TCP")

    @Test
    fun thePrivateHomeNetworkWithTheRuleIsFine() {
        val advice = NetworkAdvice.advise(network("Private", listOf(rule)), "192.168.0.102", 8765)
        assertEquals(State.Good, advice.network.state)
        assertTrue("Сеть 5" in advice.network.says, "the network of the address phones are shown, not a virtual one")
        assertEquals(State.Good, advice.firewall.state)
    }

    @Test
    fun aPublicNetworkIsToldHowToMakePrivate() {
        val advice = NetworkAdvice.advise(network("Public", listOf(rule)), "192.168.0.102", 8765)
        assertEquals(State.Problem, advice.network.state)
        assertTrue("Частная сеть" in advice.network.says && "Ethernet 4" in advice.network.says)
        assertEquals(State.Good, advice.firewall.state, "the rule is right; the network is what to change")
    }

    @Test
    fun aMissingOrWrongRuleComesWithTheCommand() {
        val none = NetworkAdvice.advise(network("Private", emptyList()), "192.168.0.102", 8765).firewall
        assertEquals(State.Problem, none.state)
        assertEquals(
            "netsh advfirewall firewall add rule name=\"Benbow Server\" dir=in action=allow protocol=TCP localport=8765 profile=private",
            none.command,
        )
        val otherPort = NetworkAdvice.advise(network("Private", listOf(rule)), "192.168.0.102", 8799).firewall
        assertEquals(State.Problem, otherPort.state)
        assertTrue("порт 8765" in otherPort.says, otherPort.says)
        assertTrue(otherPort.command!!.startsWith("netsh advfirewall firewall add rule") && "localport=8799" in otherPort.command)
        assertFalse("delete" in otherPort.command, "another server's rule on this computer stays")
        val off = NetworkAdvice.advise(network("Private", listOf(rule.copy(enabled = "False"))), "192.168.0.102", 8765).firewall
        assertTrue("выключено" in off.says)
        val noNetwork = NetworkAdvice.advise(network("Private", listOf(rule)), null, 8765).network
        assertEquals(State.Problem, noNetwork.state)
    }

    @Test
    fun theScriptsAnswerIsReadPastWhatPowerShellPrintsBeforeIt() {
        val json = """{"system":"C","volumes":[{"letter":"C","label":"Система","fs":"NTFS","type":"Fixed","size":999364956160,""" +
            """"free":22128087040,"disk":3},{"letter":"Z","label":null,"fs":"","type":"Removable","size":0,"free":0,"disk":null}],""" +
            """"disks":[{"number":3,"name":"Samsung SSD 980 1TB","media":"SSD","bus":"NVMe","size":1000204886016,"health":"Healthy"}],""" +
            """"networks":[{"name":"Сеть 5","adapter":"Ethernet 4","category":"Private","addresses":["192.168.0.102"]}],""" +
            """"rules":[{"enabled":"True","action":"Allow","direction":"Inbound","profile":"Private","ports":["8765"],"protocol":"TCP"}]}"""
        val survey = HostSurvey.parse("#< CLIXML\r\n<Objs Version=\"1.1.0.1\"></Objs>$json\r\n")
        assertEquals("Система", survey.volumes.first().label)
        assertNull(survey.volumes.last().disk)
        assertEquals(listOf("192.168.0.102"), survey.networks.single().addresses)
        assertEquals(listOf("8765"), survey.rules.single().ports)
        assertEquals(emptyList(), HostSurvey.parse("""{"system":"C","volumes":[],"disks":[],"networks":[],"rules":[]}""").rules)
    }

    @Test
    fun theSurveyIsReadAtMostOnceAMinuteAndKeepsItsLastAnswer() {
        var now = 0L
        var reads = 0
        var answer: Survey? = owners
        val survey = HostSurvey(read = {
            reads++
            answer
        }, now = { now }, run = { it.run() })
        assertEquals(owners, survey.current())
        survey.current()
        assertEquals(1, reads)
        now += HostSurvey.MAX_AGE
        answer = null
        assertEquals(owners, survey.current(), "a failed read keeps the last answer")
        assertEquals(2, reads)
        assertTrue(survey.tried)
    }

    @Test
    fun aNewServersPageSendsItsOwnerToWhereToKeepThings() {
        val data = Files.createTempDirectory("benbow-places")
        val db = Database.open(data, "test")
        val blobs = BlobStore(db, data.resolve("media"))
        // This temp folder's own letter stands in for the system disk; another disk has room for the second copy.
        val letter = PlaceAdvice.letter(data.toAbsolutePath().toString())!!
        val other = if (letter == "Q") "R" else "Q"
        val survey = Survey(
            system = letter,
            volumes = listOf(volume(letter, 0, 200 * gb), volume(other, 1, 2_000 * gb)),
            disks = listOf(disk(0, "SSD"), disk(1, "HDD")),
            networks = listOf(Survey.NetworkProfile("Сеть", "Wi-Fi", "Public", listOf("192.168.1.5"))),
            rules = listOf(rule),
        )
        val ctx = ServerContext(
            db,
            Devices(db),
            PairingCodes(),
            "AA:BB",
            data,
            8765,
            isLoopback = { true },
            blobs = blobs,
            survey = HostSurvey(read = { survey }, run = { it.run() }),
            lanAddresses = { listOf("192.168.1.5") },
        )
        testApplication {
            application { benbowModule(ctx) }
            val page = client.get("/").bodyAsText()
            assertTrue("href=\"#places\"" in page, "a tab of its own")
            assertTrue("Стоит поправить: второй диск, сеть." in page, page.substringAfter("<main>").take(1_500))
            val places = client.get("/?part=places").bodyAsText()
            assertTrue("name=\"mirror-path\" value=\"$other:\\Benbow-mirror\"" in places, places.take(2_000))
            assertTrue("action=\"/admin/mirror#backups\"" in places)
            assertTrue("общедоступная" in places)
            assertFalse("name=\"media-path\"" in places, "with two disks the media stay")
            assertNotNull(Regex("Диски этого компьютера").find(places))
        }
    }
}
