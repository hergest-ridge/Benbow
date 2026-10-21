package app.benbow.server

import app.benbow.server.backup.Disks
import java.nio.file.Path

/**
 * Disks for tests (ADR-042): temporary folders all sit on one volume, so here every folder is a volume of its own unless
 * [volumes] says otherwise, physical disks are known only from [physical], and there is room to spare.
 */
internal class TestDisks(
    private val volumes: Map<Path, String> = emptyMap(),
    private val physical: Map<Path, Int> = emptyMap(),
    private val freeBytes: Long = Long.MAX_VALUE,
    private val fileSystems: Map<Path, String> = emptyMap(),
    private val streaming: Set<Path> = emptySet(),
) : Disks {
    override fun volume(path: Path): String = volumes.entries.firstOrNull { path.startsWith(it.key) }?.value ?: path.toString()

    override fun physical(path: Path): Int? = physical.entries.firstOrNull { path.startsWith(it.key) }?.value

    override fun free(path: Path): Long = freeBytes

    override fun fileSystem(path: Path): String = fileSystems.entries.firstOrNull { path.startsWith(it.key) }?.value ?: "NTFS"

    override fun streaming(path: Path): Boolean = streaming.any { path.startsWith(it) }
}
