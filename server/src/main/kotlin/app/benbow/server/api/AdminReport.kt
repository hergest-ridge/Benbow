package app.benbow.server.api

import app.benbow.server.SERVER_VERSION
import app.benbow.server.ServerConfig
import app.benbow.server.offsite.OffsiteCopy
import app.benbow.server.peer.PeerLink
import app.benbow.server.peer.VIA_FOLDER
import app.benbow.server.peer.VIA_MAIL
import app.benbow.server.peer.VIA_PARTS_MAIL
import app.benbow.server.peer.driveFolder
import app.benbow.server.peer.filesVia
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * «Отчёт для отправки» on the admin page: one ZIP to send to whoever helps with this server (a friend's server, say),
 * since its admin page opens only on its own PC. `report.txt` says what the server is and how it stands; `logs/` holds
 * its journals and the service wrapper's. Nothing of the journal, no media, no passwords, keys or certificates: the
 * settings go with every secret blanked.
 */
internal fun Route.reportRoutes(ctx: ServerContext) {
    get("/admin/report.zip") {
        if (!adminAllowed(call, ctx)) return@get call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        val name = "benbow-report-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.zip"
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString(),
        )
        call.respondOutputStream(ContentType.Application.Zip) { writeReport(ctx, this) }
    }
}

/** The overview's card: the report, and what is and isn't in it. */
internal fun reportCard(): String = "<div class=\"card\"><h3>${icon("file")}Если что-то не так</h3>" +
    "<p class=\"lead\">Отчёт собирает журналы и состояние сервера в один файл: пошлите его тому, кто помогает с сервером. " +
    "Записей, медиафайлов, паролей и ключей в нём нет.</p>" +
    "<div class=\"row end\"><a class=\"btn tonal\" href=\"/admin/report.zip\" download>${icon("download")}" +
    "<span>Отчёт для отправки</span></a></div></div>"

internal fun writeReport(ctx: ServerContext, out: OutputStream) {
    ZipOutputStream(out).use { zip ->
        zip.putNextEntry(ZipEntry("report.txt"))
        zip.write(reportText(ctx).replace("\n", "\r\n").toByteArray())
        zip.closeEntry()
        // The server's own journals, newest first, up to a limit; the wrapper's tails (its stdout repeats the journal).
        val logs = ctx.dataDir?.resolve("logs")?.takeIf { it.isDirectory() }
        var budget = LOG_BUDGET
        logs?.let { dir -> Files.list(dir).use { s -> s.filter { it.name.endsWith(".log") }.toList() } }
            ?.sortedByDescending { Files.getLastModifiedTime(it) }
            ?.forEach { file ->
                if (budget <= 0) return@forEach
                budget -= add(zip, "logs/${file.name}", file, budget)
            }
        serviceLogs()?.let { dir ->
            Files.list(dir).use { s -> s.filter { it.name.endsWith(".wrapper.log") || it.name.endsWith(".err.log") }.toList() }
        }
            ?.forEach { add(zip, "service-logs/${it.name}", it, TAIL) }
    }
}

/** The last [limit] bytes of [file] into [zip]; how many went in. */
private fun add(zip: ZipOutputStream, name: String, file: Path, limit: Long): Long {
    val size = file.fileSize()
    val skip = (size - limit).coerceAtLeast(0)
    zip.putNextEntry(ZipEntry(name))
    var written = 0L
    Files.newInputStream(file).use { input ->
        input.skipNBytes(skip)
        written = input.copyTo(zip)
    }
    zip.closeEntry()
    return written
}

/** The service wrapper's logs: beside the runtime this server runs from (an installed package), or none. */
private fun serviceLogs(): Path? = ProcessHandle.current().info().command().orElse(null)
    ?.let { Path.of(it).parent?.parent?.parent?.resolve("service-logs") }
    ?.takeIf { it.isDirectory() }

private fun reportText(ctx: ServerContext): String = buildString {
    fun line(text: String = "") = append(text).append('\n')
    line("Benbow server report, ${time(System.currentTimeMillis())}")
    line()
    line("Server ${SERVER_VERSION}, store ${ctx.db.storeId}, epoch ${ctx.db.epoch}, port ${ctx.port}")
    line("Android app offered: ${ctx.app?.info?.let { "${it.versionName} (${it.versionCode})" } ?: "none"}")
    line(
        "Windows: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}; " +
            "Java ${System.getProperty("java.version")}, heap ${Runtime.getRuntime().maxMemory() shr 20} MiB",
    )
    ctx.dataDir?.let { data ->
        line("Data folder: $data, free ${gb(runCatching { Files.getFileStore(data).usableSpace }.getOrNull())}")
        line()
        line("Settings (secrets blanked):")
        val settings = data.resolve(ServerConfig.FILE_NAME)
        if (settings.isRegularFile()) {
            settings.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { raw ->
                val key = raw.substringBefore('=')
                line("  " + if (SECRET.containsMatchIn(key)) "$key=(hidden)" else raw)
            }
        }
    }
    line()
    ctx.blobs?.let { blobs ->
        val (count, bytes) = blobs.stats()
        line("Media: ${blobs.root} (${if (blobs.ready()) "there" else "NOT THERE"}), $count files, ${gb(bytes)}")
    }
    ctx.backupStatus()?.let { s ->
        line("Snapshots: last ${time(s.lastSnapshotAt)}${s.lastError?.let { ", last error: $it" }.orEmpty()}")
        line(
            "Second disk: ${s.mirrorPath ?: "none"}, last full copy ${time(s.lastMirrorAt)}${s.mirrorProblem?.let {
                ", problem: $it"
            }.orEmpty()}",
        )
        s.offsiteError?.let { line("Copy outside the house failed: $it") }
    }
    ctx.offsite?.let { line("Copy outside the house: ${offsiteLine(it)}") }
    line()
    val devices = ctx.devices.list()
    line("Devices: ${devices.count { it.revokedAt == null }} paired")
    devices.forEach { d ->
        line(
            "  ${d.name} (${d.platform}), paired ${time(d.createdAt)}, last seen ${time(d.lastSeenAt)}${d.revokedAt?.let {
                ", revoked ${time(it)}"
            }.orEmpty()}",
        )
    }
    line()
    ctx.peers?.let { line(peersText(it)) } ?: line("Friends: off (the link's store didn't open; see the log)")
    val problems = placesProblems(ctx)
    line("«Где хранить» says to fix: ${problems.ifEmpty { listOf("nothing") }.joinToString()}")
}

private fun offsiteLine(copy: OffsiteCopy): String {
    val root = copy.root ?: return "off"
    val passes = copy.key()?.let { copy.folder(it)?.passes() }
    return "$root, last full copy ${time(passes?.lastAt)}, last clean check ${time(passes?.checkedAt)}" +
        (passes?.checkProblem?.let { ", check found: $it" }.orEmpty()) + ", last pass: ${copy.last}"
}

private fun peersText(peers: PeerLink): String = buildString {
    fun line(text: String = "") = append(text).append('\n')
    line("Friends: this server's card ${peers.identity.card.id}, fingerprint ${peers.identity.card.fingerprint}")
    val mail = peers.db.mailSettings()
    line("  Mailbox: ${mail?.let { "${it.host}:${it.port}, ${it.user}" } ?: "not set"}; status ${statusLine(peers.status)}")
    val parts = peers.db.partsMailSettings()
    line("  Files go: ${peers.db.filesVia()}; parts mailbox: ${parts?.let { "${it.host}:${it.port}, ${it.user}" } ?: "not set"}")
    for (via in listOf(VIA_MAIL, VIA_PARTS_MAIL, VIA_FOLDER)) peers.filesTrouble(via)?.let { line("  Files $via: $it") }
    line("  Shared folder: ${peers.db.driveFolder()?.let { "$it${if (it.exists()) "" else " (NOT THERE)"}" } ?: "not set"}")
    for (contact in peers.db.contacts()) {
        val notes = peers.db.notes(contact.id, 200)
        val seen = peers.db.seen(contact.id)
        line(
            "  ${contact.name}: card ${contact.card.id}, server ${seen?.let {
                "${it.version} (letter ${time(it.at)})"
            } ?: "not known yet"}, " +
                "unacknowledged ${notes.count { it.outgoing && it.ackedAt == null }} of the last ${notes.size} notes",
        )
    }
}

private fun statusLine(status: PeerLink.Status): String = when (status) {
    PeerLink.Status.Off -> "off"

    PeerLink.Status.Connecting -> "connecting"

    is PeerLink.Status.Online -> "online since ${time(status.since)}, last round ${time(status.lastRoundAt)}: ${status.lastRound}"

    is PeerLink.Status.Failed -> "failed ${time(status.at)}: ${status.message}${status.retryAt?.let {
        ", retry ${time(it)}"
    } ?: ", not retrying"}"
}

private fun gb(bytes: Long?) = bytes?.let { "%.1f GB".format(it / 1_073_741_824.0) } ?: "?"

/** Setting names whose values never leave the PC. */
private val SECRET = Regex("password|secret|token|key", RegexOption.IGNORE_CASE)

private const val LOG_BUDGET = 20L * 1024 * 1024
private const val TAIL = 512L * 1024
