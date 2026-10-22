package app.benbow.server.setup

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Where Benbow's three places stand on this PC's disks, and what would be better (ADR-042, as built 7.19 I1): the
 * database with its snapshots (it stays where the server was installed), the media, the second disk. The second disk
 * comes first, since it is what saves the journal when a disk dies; then the media off the system disk, never onto the
 * second disk's physical disk. A suggestion only fills in a form the admin page already has: the same checks run before
 * anything is saved or copied.
 */
object PlaceAdvice {
    enum class State { Good, Note, Problem }

    /** One place: where it is, what is said about it, and a better folder when there is one. */
    data class Place(
        val path: String?,
        val volume: Survey.Volume?,
        val disk: Survey.PhysicalDisk?,
        val state: State,
        val says: String,
        val better: Better? = null,
    )

    /** A folder suggested instead, on [volume]. */
    data class Better(val path: String, val volume: Survey.Volume, val disk: Survey.PhysicalDisk?)

    /** A volume with its physical disk and what Benbow keeps there. */
    data class DiskRow(val volume: Survey.Volume, val disk: Survey.PhysicalDisk?, val system: Boolean, val holds: List<String>)

    data class Advice(val database: Place, val media: Place, val mirror: Place, val disks: List<DiskRow>)

    /**
     * [dataDir], [media] and [mirror] as absolute Windows paths; [mediaBytes] what the media weigh now. [taken] says a
     * folder can't be suggested (it exists and holds something).
     */
    fun advise(
        survey: Survey,
        dataDir: String,
        media: String,
        mediaBytes: Long,
        mirror: String?,
        taken: (String) -> Boolean = ::taken,
    ): Advice {
        val disks = Disks(survey)
        val dbVolume = disks.volumeOf(dataDir)
        val mediaVolume = disks.volumeOf(media)
        val mirrorVolume = mirror?.let(disks::volumeOf)

        val avoid = setOfNotNull(dbVolume?.disk, mediaVolume?.disk, mirrorVolume?.disk)
        val mirrorPick = disks.forMirror(avoid, need = mediaBytes + MIRROR_ROOM)?.let { disks.better(it, "Benbow-mirror", taken) }
        val mirrorPlace = mirrorPlace(disks, mirror, mirrorVolume, dbVolume, mediaVolume, mirrorPick)

        val mirrorDisk = mirrorVolume?.disk ?: mirrorPlace.better?.volume?.disk
        val mediaPick = disks.forMedia(avoid = setOfNotNull(mirrorDisk), need = mediaBytes + MEDIA_ROOM)
            ?.let { disks.better(it, "Benbow-media", taken) }
        val mediaPlace = mediaPlace(disks, media, mediaVolume, mediaPick)

        val rows = survey.volumes.sortedBy { it.letter }.map { volume ->
            val holds = listOfNotNull(
                "база".takeIf { volume == dbVolume },
                "медиафайлы".takeIf { volume == mediaVolume },
                "второй диск".takeIf { volume == mirrorVolume },
            )
            DiskRow(volume, disks.diskOf(volume), disks.isSystem(volume), holds)
        }
        return Advice(databasePlace(disks, dataDir, dbVolume), mediaPlace, mirrorPlace, rows)
    }

    private fun databasePlace(disks: Disks, path: String, volume: Survey.Volume?): Place {
        val disk = volume?.let(disks::diskOf)
        fun place(state: State, says: String) = Place(path, volume, disk, state, says)
        return when {
            volume == null -> place(State.Note, "Windows не сказал, на каком диске эта папка.")

            !healthy(disk) -> place(
                State.Problem,
                "Windows считает диск ${disk?.name.orEmpty()} неисправным (${disk?.health}): держите второй диск выбранным и копию свежей.",
            )

            volume.free < DATABASE_LOW -> place(
                State.Problem,
                "На ${volume.letter}: осталось ${gb(volume.free)} ГБ: когда место кончится, записи перестанут сохраняться.",
            )

            else -> place(State.Good, "База и её снимки занимают немного; им хорошо там, где установлен сервер.")
        }
    }

    private fun mediaPlace(disks: Disks, path: String, volume: Survey.Volume?, pick: Better?): Place {
        val disk = volume?.let(disks::diskOf)
        fun place(state: State, says: String, better: Better? = pick) = Place(path, volume, disk, state, says, better)
        return when {
            volume == null -> place(
                State.Problem,
                "Диска ${letter(path).orEmpty()}: сейчас нет: файлы не принимаются и не отдаются, пока его не подключат.",
                better = null,
            )

            !healthy(disk) -> place(
                State.Problem,
                "Windows считает диск ${disk?.name.orEmpty()} неисправным (${disk?.health}): медиафайлы лучше перенести.",
            )

            disks.removable(volume) -> place(
                State.Problem,
                "Медиафайлы на съёмном диске: отключат его, и файлы перестанут приниматься и отдаваться.",
            )

            volume.free < LOW -> place(
                State.Problem,
                "На ${volume.letter}: осталось ${gb(volume.free)} ГБ: новые фото и видео скоро некуда будет класть.",
            )

            disks.isSystem(volume) && pick != null -> place(
                State.Note,
                "Медиафайлы на системном диске: они растут быстрее всего, и место на нём кончится раньше, чем на других.",
            )

            disks.isSystem(volume) -> place(State.Good, "Медиафайлы на системном диске: другого подходящего диска нет, так можно.")

            else -> place(State.Good, "Медиафайлы не на системном диске: им есть где расти.", better = null)
        }
    }

    private fun mirrorPlace(
        disks: Disks,
        path: String?,
        volume: Survey.Volume?,
        dbVolume: Survey.Volume?,
        mediaVolume: Survey.Volume?,
        pick: Better?,
    ): Place {
        val disk = volume?.let(disks::diskOf)
        fun place(state: State, says: String, better: Better? = pick) = Place(path, volume, disk, state, says, better)
        val noOther = if (pick == null) " Другого подходящего диска на этом компьютере нет: подключите внешний." else ""
        return when {
            path == null -> place(
                State.Problem,
                "Второго диска нет: если диск с базой или медиафайлами сломается, останется только то, что есть на устройствах.$noOther",
            )

            volume == null -> place(
                State.Problem,
                "Диска ${letter(path).orEmpty()}: сейчас нет: копии на второй диск не делаются, пока его не подключат.",
            )

            !healthy(disk) -> place(
                State.Problem,
                "Windows считает диск ${disk?.name.orEmpty()} неисправным (${disk?.health}): копия на нём ненадёжна.$noOther",
            )

            volume.disk != null && (volume.disk == dbVolume?.disk || volume.disk == mediaVolume?.disk) -> place(
                State.Note,
                "Это тот же физический диск, где база или медиафайлы: от его поломки такая копия не спасёт.$noOther",
            )

            volume.free < LOW -> place(
                State.Problem,
                "На ${volume.letter}: осталось ${gb(volume.free)} ГБ: скоро новые копии туда не поместятся.$noOther",
            )

            mediaVolume != null && volume.free < minOf(mediaVolume.free, FILLS_FIRST) -> place(
                State.Note,
                "Он заполнится раньше диска с медиафайлами: здесь свободно ${gb(volume.free)} ГБ, на ${mediaVolume.letter}: " +
                    "${gb(mediaVolume.free)} ГБ.",
                better = pick?.takeIf { it.volume.free > volume.free * 2 },
            )

            disks.removable(volume) -> place(State.Note, "Диск съёмный: копии идут, только пока он подключён.", better = null)

            else -> place(State.Good, "Копия на другом физическом диске: если один диск сломается, всё останется на другом.", null)
        }
    }

    private fun taken(path: String): Boolean {
        val p = Path.of(path)
        return p.exists() && (!p.isDirectory() || Files.list(p).use { it.findAny().isPresent })
    }

    private class Disks(private val survey: Survey) {
        fun volumeOf(path: String): Survey.Volume? = letter(path)?.let { l -> survey.volumes.firstOrNull { it.letter.equals(l, true) } }

        fun diskOf(volume: Survey.Volume): Survey.PhysicalDisk? = survey.disks.firstOrNull { it.number == volume.disk }

        fun isSystem(volume: Survey.Volume): Boolean {
            val system = survey.volumes.firstOrNull { it.letter.equals(survey.system, true) }
            return volume == system || (volume.disk != null && volume.disk == system?.disk)
        }

        fun removable(volume: Survey.Volume) = volume.type == "Removable" || diskOf(volume)?.bus.equals("USB", true)

        /** `X:\name` on [volume], or `X:\name-2`... when that one is taken. */
        fun better(volume: Survey.Volume, name: String, taken: (String) -> Boolean): Better? =
            (1..9).map { "${volume.letter}:\\" + if (it == 1) name else "$name-$it" }.firstOrNull { !taken(it) }
                ?.let { Better(it, volume, diskOf(volume)) }

        /** Another physical disk with room: internal before external, then the most free. */
        fun forMirror(avoid: Set<Int>, need: Long): Survey.Volume? = survey.volumes
            .filter { it.disk != null && it.disk !in avoid && it.free >= need && healthy(diskOf(it)) }
            .filter { it.fs.orEmpty().uppercase() in MIRROR_FILE_SYSTEMS && it.type in listOf("Fixed", "Removable") }
            .sortedWith(compareBy<Survey.Volume> { removable(it) }.thenByDescending { it.free })
            .firstOrNull()

        /** A fixed disk other than the system's and the second disk's, with room: SSD first, then the most free. */
        fun forMedia(avoid: Set<Int>, need: Long): Survey.Volume? = survey.volumes
            .filter { it.disk != null && it.disk !in avoid && !isSystem(it) && it.type == "Fixed" && !removable(it) }
            .filter { it.fs.orEmpty().uppercase() in MEDIA_FILE_SYSTEMS && it.free >= need && healthy(diskOf(it)) }
            .sortedWith(compareBy<Survey.Volume> { !diskOf(it)?.media.equals("SSD", true) }.thenByDescending { it.free })
            .firstOrNull()
    }

    /** Unknown counts as healthy: only what Windows itself reports as Warning or Unhealthy is said. */
    private fun healthy(disk: Survey.PhysicalDisk?) = disk?.health.let { it.isNullOrEmpty() || it.equals("Healthy", true) }

    /** The drive letter of a Windows path, upper case. */
    fun letter(path: String): String? = Regex("""^([A-Za-z]):[\\/]""").find(path)?.groupValues?.get(1)?.uppercase()

    /** Gigabytes (GiB) for people: one decimal below ten. */
    fun gb(bytes: Long): String = (bytes / 1_073_741_824.0).let { if (it < 10) "%.1f".format(it) else "%.0f".format(it) }

    private const val GB = 1_073_741_824L
    private const val LOW = 10 * GB
    private const val DATABASE_LOW = 5 * GB
    private const val FILLS_FIRST = 100 * GB
    private const val MIRROR_ROOM = 25 * GB
    private const val MEDIA_ROOM = 50 * GB
    private val MIRROR_FILE_SYSTEMS = setOf("NTFS", "REFS", "EXFAT")
    private val MEDIA_FILE_SYSTEMS = setOf("NTFS", "REFS")
}
