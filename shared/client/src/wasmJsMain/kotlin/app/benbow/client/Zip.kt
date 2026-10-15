@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.client

import kotlinx.coroutines.await
import kotlinx.datetime.LocalDateTime
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeIntLe
import kotlinx.io.writeLongLe
import kotlinx.io.writeShortLe
import kotlin.js.Promise

// ZIP in the browser (ADR-029): the journal's export assembled from parts, where media stay the browser's Blobs and
// are never read whole into memory, and calendar archives read for import. Entries are stored (the export's files are
// pictures and videos, compressed already, and small text); ZIP64 records appear where sizes or offsets need them.

private const val MAX32 = 0xFFFFFFFFL
private const val UTF8_NAMES: Short = 0x0800

/** Collects the archive's files; [finish] gives the whole ZIP as one Blob for the browser's download. */
class BlobZipWriter(at: LocalDateTime) : ArchiveWriter {
    private val parts = newParts()
    private val records = ArrayList<Record>()
    private var offset = 0L
    private val time = (at.hour shl 11 or (at.minute shl 5) or (at.second / 2)).toShort()
    private val date = ((at.year - 1980).coerceIn(0, 127) shl 9 or (at.month.ordinal + 1 shl 5) or at.day).toShort()

    private class Record(val name: ByteArray, val crc: Int, val size: Long, val offset: Long)

    override suspend fun text(path: String, text: String) = add(path, text.encodeToByteArray().toUint8Array())

    override suspend fun file(path: String, file: LocalFile) = add(path, file.blob)

    private suspend fun add(path: String, data: JsAny) {
        val size = dataSize(data).toLong()
        val crc = crc32(data).await<JsNumber>().toDouble().toLong().toInt()
        val name = path.encodeToByteArray()
        val zip64 = size >= MAX32 || offset >= MAX32
        val header = Buffer().apply {
            writeIntLe(0x04034b50)
            writeShortLe(if (zip64) 45 else 20)
            writeShortLe(UTF8_NAMES)
            writeShortLe(0) // stored
            writeShortLe(time)
            writeShortLe(date)
            writeIntLe(crc)
            writeIntLe(if (zip64) -1 else size.toInt())
            writeIntLe(if (zip64) -1 else size.toInt())
            writeShortLe(name.size.toShort())
            writeShortLe(if (zip64) 20 else 0)
            write(name)
            if (zip64) {
                writeShortLe(1)
                writeShortLe(16)
                writeLongLe(size)
                writeLongLe(size)
            }
        }.readByteArray()
        addPart(parts, header.toUint8Array())
        addPart(parts, data)
        records += Record(name, crc, size, offset)
        offset += header.size + size
    }

    fun finish(): JsAny {
        val start = offset
        val directory = Buffer()
        for (r in records) {
            val zip64 = r.size >= MAX32 || r.offset >= MAX32
            directory.apply {
                writeIntLe(0x02014b50)
                writeShortLe(45)
                writeShortLe(if (zip64) 45 else 20)
                writeShortLe(UTF8_NAMES)
                writeShortLe(0)
                writeShortLe(time)
                writeShortLe(date)
                writeIntLe(r.crc)
                writeIntLe(if (zip64) -1 else r.size.toInt())
                writeIntLe(if (zip64) -1 else r.size.toInt())
                writeShortLe(r.name.size.toShort())
                writeShortLe(if (zip64) 28 else 0)
                writeShortLe(0) // comment
                writeShortLe(0) // disk
                writeShortLe(0) // internal attributes
                writeIntLe(0) // external attributes
                writeIntLe(if (zip64) -1 else r.offset.toInt())
                write(r.name)
                if (zip64) {
                    writeShortLe(1)
                    writeShortLe(24)
                    writeLongLe(r.size)
                    writeLongLe(r.size)
                    writeLongLe(r.offset)
                }
            }
        }
        val directoryBytes = directory.readByteArray()
        val size = directoryBytes.size.toLong()
        val count = records.size.toLong()
        val zip64 = count >= 0xFFFF || start >= MAX32 || size >= MAX32
        val end = Buffer().apply {
            if (zip64) {
                writeIntLe(0x06064b50)
                writeLongLe(44)
                writeShortLe(45)
                writeShortLe(45)
                writeIntLe(0)
                writeIntLe(0)
                writeLongLe(count)
                writeLongLe(count)
                writeLongLe(size)
                writeLongLe(start)
                writeIntLe(0x07064b50)
                writeIntLe(0)
                writeLongLe(start + size)
                writeIntLe(1)
            }
            writeIntLe(0x06054b50)
            writeShortLe(0)
            writeShortLe(0)
            writeShortLe(if (zip64) -1 else count.toInt().toShort())
            writeShortLe(if (zip64) -1 else count.toInt().toShort())
            writeIntLe(if (zip64) -1 else size.toInt())
            writeIntLe(if (zip64) -1 else start.toInt())
            writeShortLe(0)
        }.readByteArray()
        addPart(parts, directoryBytes.toUint8Array())
        addPart(parts, end.toUint8Array())
        return blobOfParts(parts, "application/zip")
    }
}

/** The files of a ZIP held in memory (a calendar archive); deflated ones are inflated by the browser itself. */
internal suspend fun readZip(bytes: ByteArray, maxTotal: Long): List<Pair<String, ByteArray>> {
    val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 0xFFFF)).firstOrNull { bytes.le32(it) == 0x06054b50L }
        ?: throw IllegalArgumentException("Not a ZIP archive")
    var count = bytes.le16(end + 10).toLong()
    var at = bytes.le32(end + 16)
    val locator = end - 20
    if ((count == 0xFFFFL || at == MAX32) && locator >= 0 && bytes.le32(locator) == 0x07064b50L) {
        val record = bytes.le64(locator + 8).toInt()
        count = bytes.le64(record + 32)
        at = bytes.le64(record + 48)
    }
    val files = mutableListOf<Pair<String, ByteArray>>()
    var total = 0L
    var p = at.toInt()
    repeat(count.toInt()) {
        require(bytes.le32(p) == 0x02014b50L) { "Damaged ZIP directory" }
        val method = bytes.le16(p + 10)
        var compressed = bytes.le32(p + 20)
        var size = bytes.le32(p + 24)
        val nameLength = bytes.le16(p + 28)
        val extraLength = bytes.le16(p + 30)
        var local = bytes.le32(p + 42)
        val name = bytes.decodeToString(p + 46, p + 46 + nameLength)
        var x = p + 46 + nameLength
        while (x < p + 46 + nameLength + extraLength) {
            val tag = bytes.le16(x)
            var v = x + 4
            if (tag == 1) {
                if (size == MAX32) size = bytes.le64(v).also { v += 8 }
                if (compressed == MAX32) compressed = bytes.le64(v).also { v += 8 }
                if (local == MAX32) local = bytes.le64(v)
            }
            x += 4 + bytes.le16(x + 2)
        }
        p += 46 + nameLength + extraLength + bytes.le16(p + 32)
        if (name.endsWith("/")) return@repeat
        total += size
        require(total <= maxTotal) { "The archive unpacks to more than ${maxTotal / 1024 / 1024} MB" }
        val l = local.toInt()
        require(bytes.le32(l) == 0x04034b50L) { "Damaged ZIP entry $name" }
        val from = l + 30 + bytes.le16(l + 26) + bytes.le16(l + 28)
        val raw = bytes.copyOfRange(from, from + compressed.toInt())
        val data = when (method) {
            0 -> raw
            8 -> inflateRaw(raw.toUint8Array()).await<JsAny>().toByteArray()
            else -> throw IllegalArgumentException("$name is packed in a way Benbow can't read (method $method)")
        }
        require(data.size.toLong() == size) { "$name didn't unpack to its stated size" }
        files += name to data
    }
    return files
}

private fun ByteArray.le16(at: Int): Int = (this[at].toInt() and 0xFF) or (this[at + 1].toInt() and 0xFF shl 8)

private fun ByteArray.le32(at: Int): Long = (le16(at).toLong()) or (le16(at + 2).toLong() shl 16)

private fun ByteArray.le64(at: Int): Long = le32(at) or (le32(at + 4) shl 32)

private fun dataSize(data: JsAny): Double = js("data instanceof Uint8Array ? data.length : data.size")

/** CRC-32 over a Uint8Array or a Blob (read in 4 MB slices), as ZIP needs it. */
private fun crc32(data: JsAny): Promise<JsNumber> = js(
    """(async () => {
        const table = globalThis.benbowCrcTable ??= (() => {
            const t = new Int32Array(256);
            for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c; }
            return t;
        })();
        let crc = -1;
        const update = bytes => { for (let i = 0; i < bytes.length; i++) crc = table[(crc ^ bytes[i]) & 0xFF] ^ (crc >>> 8); };
        if (data instanceof Uint8Array) update(data);
        else for (let at = 0; at < data.size; at += 4194304) update(new Uint8Array(await data.slice(at, at + 4194304).arrayBuffer()));
        return (crc ^ -1) >>> 0;
    })()""",
)

private fun inflateRaw(bytes: JsAny): Promise<JsAny> = js(
    """new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).arrayBuffer().then(b => new Uint8Array(b))""",
)
