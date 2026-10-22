package app.benbow.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/** Settings from `<data>/benbow.properties`, created with defaults on first run. */
data class ServerConfig(
    val host: String,
    val port: Int,
    val tlsPassword: String,
    /** Second location for backup copies (e.g. another disk); empty = disabled (data-safety.md#backups). */
    val backupMirror: Path? = null,
    val backupIntervalMinutes: Long = 60,
    /** Where media files live (ADR-009); empty = `<data>/media`. */
    val mediaRoot: Path? = null,
    /** The encrypted copy outside the house (ADR-043); empty = none. */
    val backupOffsite: Path? = null,
) {
    companion object {
        const val FILE_NAME = "benbow.properties"

        /** `--data <dir>`, then `BENBOW_DATA`, then `%LOCALAPPDATA%\Benbow` (or `~/.benbow`). */
        fun resolveDataDir(args: Array<String>): Path {
            val flag = args.indexOf("--data")
            if (flag >= 0 && flag + 1 < args.size) return Path.of(args[flag + 1])
            System.getenv("BENBOW_DATA")?.let { return Path.of(it) }
            val local = System.getenv("LOCALAPPDATA")
            return if (local != null) Path.of(local, "Benbow") else Path.of(System.getProperty("user.home"), ".benbow")
        }

        fun load(dataDir: Path): ServerConfig {
            val file = dataDir.resolve(FILE_NAME)
            val props = Properties()
            if (file.exists()) file.inputStream().use(props::load)
            val defaults = mapOf(
                "host" to "0.0.0.0",
                "port" to DEFAULT_PORT.toString(),
                "tls.password" to randomSecret(),
                "backup.mirror" to "",
                "backup.intervalMinutes" to "60",
                "media.root" to "",
                "backup.offsite" to "",
            )
            val missing = defaults.filterKeys { props.getProperty(it) == null }
            if (missing.isNotEmpty()) {
                missing.forEach { (k, v) -> props.setProperty(k, v) }
                writeAtomically(file, props)
            }
            return ServerConfig(
                host = props.getProperty("host"),
                port = props.getProperty("port").toInt(),
                tlsPassword = props.getProperty("tls.password"),
                backupMirror = props.getProperty("backup.mirror").trim().takeIf { it.isNotEmpty() }?.let(Path::of),
                backupIntervalMinutes = props.getProperty("backup.intervalMinutes").toLong().coerceIn(5, 24 * 60),
                mediaRoot = props.getProperty("media.root").trim().takeIf { it.isNotEmpty() }?.let(Path::of),
                backupOffsite = props.getProperty("backup.offsite").trim().takeIf { it.isNotEmpty() }?.let(Path::of),
            )
        }

        /** Sets [values] in `<data>/benbow.properties` in one atomic write, keeping every other line's value (ADR-042, P3). */
        fun update(dataDir: Path, values: Map<String, String>) {
            val file = dataDir.resolve(FILE_NAME)
            val props = Properties()
            if (file.exists()) file.inputStream().use(props::load)
            values.forEach { (key, value) -> props.setProperty(key, value) }
            writeAtomically(file, props)
        }

        private fun writeAtomically(file: Path, props: Properties) {
            file.parent.createDirectories()
            val tmp = file.resolveSibling("$FILE_NAME.tmp")
            tmp.outputStream().use {
                props.store(it, "Benbow server settings")
                (it as? java.io.FileOutputStream)?.fd?.sync()
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        private fun randomSecret(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
    }
}
