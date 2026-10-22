package app.benbow.server.offsite

import app.benbow.server.storage.Backups
import app.benbow.server.storage.BlobStore
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.random.Random

/**
 * The check of the copy outside the house (ADR-043, as built 7.7 E3): its files opened with the key and compared with
 * home, as the local snapshots are (S9). The newest journal and calendar snapshots are decrypted and must pass
 * `integrity_check` and hold the rows their manifest says; the manifests, the purge journal and the two archives must
 * decrypt to what was written; a sample of media must decrypt to their hash. A file that doesn't is removed, so the
 * next pass writes it again from home; when many fail at once something else is wrong (a disk, a sync), and nothing is
 * removed, only said.
 */
class OffsiteCheck internal constructor(
    private val target: Path,
    private val key: OffsiteKey,
    /** A scratch file at home for a decrypted snapshot. */
    private val scratch: Path,
    private val random: Random = Random.Default,
) {
    private val log = LoggerFactory.getLogger("benbow.offsite")

    /** [bad]: files that didn't open as they should (removed unless [held]); [missing]: ones that should be there. */
    data class Result(val checked: Int, val bad: List<String>, val missing: List<String>, val held: Boolean) {
        val ok get() = bad.isEmpty() && missing.isEmpty()
    }

    private val checked = ArrayList<String>()
    private val bad = ArrayList<Path>()
    private val missing = ArrayList<String>()

    /**
     * Checks [snapshots] (each folder's newest), the small files in [written] (their name to the SHA-256 written) and up to
     * [sample] of [media]; drops what [written] said of a removed file, so it is written again.
     */
    fun run(
        snapshots: Map<String, Backups>,
        written: MutableMap<String, String>,
        media: List<BlobStore.Blob>,
        sample: Int = SAMPLE,
    ): Result {
        for ((folder, backups) in snapshots) snapshot(folder, backups)
        for ((name, sha) in written.toMap()) {
            val file = target.resolve("$name.age")
            if (!opensAs(file, sha, name)) written.remove(name)
        }
        val present = media.filter { copyOf(it.sha256).isRegularFile() }
        for (blob in present.shuffled(random).take(sample)) opensAs(copyOf(blob.sha256), blob.sha256, "media/${blob.path}")
        // Many at once is a disk or a sync gone wrong, not a few bad files: then nothing is removed.
        val held = bad.size > maxOf(3, checked.size / 2)
        if (!held) {
            bad.forEach { file ->
                file.deleteIfExists()
                log.warn("Removed from the copy outside the house, to be written again: {}", target.relativize(file))
            }
        }
        return Result(checked.size, bad.map { target.relativize(it).joinToString("/") }, missing.toList(), held)
    }

    private fun copyOf(sha: String) = key.mediaName(sha).let { target.resolve("media").resolve(it.take(2)).resolve("$it.age") }

    /** The newest verified snapshot there: decrypted at home, the same bytes as here, `integrity_check` and the rows; then the manifest. */
    private fun snapshot(folder: String, backups: Backups) {
        val newest = backups.history().lastOrNull { it.verified } ?: return
        val home = backups.directory.resolve(newest.file)
        val copy = target.resolve(folder).resolve("${newest.file}.age")
        if (!copy.isRegularFile()) {
            missing += "$folder/${newest.file}"
        } else {
            checked += copy.toString()
            val good = try {
                val sha = AgeFiles.decrypt(copy, scratch, key)
                (!home.isRegularFile() || sha == sha256(home)) && backups.verify(scratch, newest.rows)
            } catch (e: Exception) {
                log.warn("{} doesn't decrypt: {}", copy, e.message)
                false
            } finally {
                scratch.deleteIfExists()
            }
            if (!good) bad.add(copy)
        }
        val manifest = target.resolve(folder).resolve("manifest.json.age")
        if (manifest.isRegularFile()) {
            checked += manifest.toString()
            val same = runCatching {
                AgeFiles.decrypt(manifest.readBytes(), key).contentEquals(backups.manifest.readBytes())
            }.getOrDefault(false)
            if (!same) bad.add(manifest)
        }
    }

    private fun opensAs(file: Path, sha: String, name: String): Boolean {
        if (!file.isRegularFile()) {
            missing += name
            return false
        }
        checked += file.toString()
        val good = runCatching { AgeFiles.sha256(file, key) == sha }.getOrDefault(false)
        if (!good) bad.add(file)
        return good
    }

    private fun sha256(file: Path): String = BlobStore.sha256(file)

    companion object {
        const val SAMPLE = 20
    }
}
