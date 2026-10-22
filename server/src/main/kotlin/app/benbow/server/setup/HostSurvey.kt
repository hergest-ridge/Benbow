package app.benbow.server.setup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText

/** What Windows says about this PC's disks and home network, as one PowerShell script reads it (see [HostSurvey]). */
@Serializable
data class Survey(
    /** The letter Windows runs from. */
    val system: String = "C",
    val volumes: List<Volume> = emptyList(),
    val disks: List<PhysicalDisk> = emptyList(),
    val networks: List<NetworkProfile> = emptyList(),
    /** The firewall rules named "Benbow Server" (install-service.cmd adds one). */
    val rules: List<FirewallRule> = emptyList(),
) {
    /** A volume with a letter; [type] Fixed, Removable..., [disk] the physical disk's number. */
    @Serializable
    data class Volume(
        val letter: String,
        val label: String? = null,
        val fs: String? = null,
        val type: String? = null,
        val size: Long = 0,
        val free: Long = 0,
        val disk: Int? = null,
    )

    /** [media] SSD, HDD or Unspecified; [bus] NVMe, SATA, USB...; [health] Healthy, Warning, Unhealthy. */
    @Serializable
    data class PhysicalDisk(
        val number: Int,
        val name: String? = null,
        val media: String? = null,
        val bus: String? = null,
        val size: Long = 0,
        val health: String? = null,
    )

    /** A connected network: [category] Public, Private or DomainAuthenticated; [addresses] its adapter's IPv4 ones. */
    @Serializable
    data class NetworkProfile(
        val name: String? = null,
        val adapter: String? = null,
        val category: String? = null,
        val addresses: List<String> = emptyList(),
    )

    /** [profile] "Private", "Domain, Private", "Any"...; [ports] local ports, or "Any". */
    @Serializable
    data class FirewallRule(
        val enabled: String? = null,
        val action: String? = null,
        val direction: String? = null,
        val profile: String? = null,
        val ports: List<String> = emptyList(),
        val protocol: String? = null,
    )
}

/**
 * This PC's disks and home network for «Где хранить» (ADR-042, as built 7.19 I1): volumes with their physical disks
 * (SSD or HDD, bus, health), the network profiles with their addresses, the firewall rule. The script only reads and
 * takes about five seconds, so it runs in the background, at most once a minute while the page asks; the page shows the
 * last answer meanwhile.
 */
class HostSurvey(
    private val read: () -> Survey?,
    private val now: () -> Long = System::currentTimeMillis,
    private val run: (Runnable) -> Unit = { Thread(it, "host-survey").apply { isDaemon = true }.start() },
) {
    @Volatile
    var last: Survey? = null
        private set

    /** Whether a read has ended at least once (with an answer or without one). */
    @Volatile
    var tried = false
        private set

    @Volatile
    private var at = Long.MIN_VALUE / 2
    private val reading = AtomicBoolean(false)

    /** The last answer (null until the first one is in), and a new read started when it's older than a minute. */
    fun current(): Survey? {
        if (now() - at >= MAX_AGE && reading.compareAndSet(false, true)) {
            run(
                Runnable {
                    try {
                        read()?.let { last = it }
                    } finally {
                        at = now()
                        tried = true
                        reading.set(false)
                    }
                },
            )
        }
        return last
    }

    companion object {
        const val MAX_AGE = 60_000L
        private val log = LoggerFactory.getLogger(HostSurvey::class.java)
        private val json = Json { ignoreUnknownKeys = true }

        /** Null away from Windows. */
        fun system(): HostSurvey? = if (System.getProperty("os.name").startsWith("Windows")) HostSurvey(::windows) else null

        /** The JSON in the script's output (anything PowerShell printed before it skipped). */
        internal fun parse(text: String): Survey =
            json.decodeFromString(Survey.serializer(), text.substring(text.indexOf("{\"system\"").coerceAtLeast(0)))

        private fun windows(): Survey? = runCatching {
            val script = HostSurvey::class.java.getResource("survey.ps1")!!.readText()
            val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
            val out = Files.createTempFile("benbow-survey", ".json")
            try {
                val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                    .redirectOutput(out.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return null
                }
                parse(out.readText())
            } finally {
                out.deleteIfExists()
            }
        }.onFailure { log.warn("Couldn't read this PC's disks and network", it) }.getOrNull()
    }
}
