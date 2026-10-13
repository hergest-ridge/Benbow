package app.benbow.client

import app.benbow.core.api.KnownEntry
import app.benbow.core.api.PairResponse
import app.benbow.core.api.Protocol
import app.benbow.core.api.PushResponse
import app.benbow.core.api.ReconcileResponse
import app.benbow.core.api.RevisionInfo
import app.benbow.core.api.ServerInfo
import app.benbow.core.api.UploadStart
import app.benbow.core.api.UploadStatus
import app.benbow.core.sync.PullResponse
import app.benbow.core.sync.RevisionRecord
import app.benbow.core.sync.SyncOp
import app.benbow.core.sync.SyncRules
import app.benbow.testing.InMemorySyncStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The server's rules over an in-memory store, plus a blob store; [log] lets a test take a "backup". */
internal class FakeServer(val storeId: String = "store-1", val epoch: Long = 1) {
    val store = InMemorySyncStore()
    val rules = SyncRules(store)
    val log = mutableListOf<SyncOp>()
    val blobs = mutableMapOf<String, ByteArray>()
    val uploads = mutableMapOf<String, Pair<UploadStart, ByteArrayOutputStream>>()

    /** A copy of this server as a backup would have it now (files included), restored under a new epoch. */
    fun restoredCopy(epoch: Long, withFiles: Boolean = true) = FakeServer(storeId, epoch).also {
        it.rules.push(log)
        it.log += log
        if (withFiles) it.blobs += blobs
    }
}

internal class FakeApi(var server: FakeServer, var serverStore: String = server.storeId) : BenbowApi {
    var dropRequest = false
    var dropResponse = false

    /** Fail after this many accepted chunks (the network dies mid-upload). */
    var failAfterChunks: Int? = null
    var corruptDownloads = false

    /** e.g. the server's disk is full (HTTP 507). */
    var uploadsRefused: ApiException? = null
    var chunksAccepted = 0

    override suspend fun info() = ServerInfo(
        "test",
        Protocol.MIN,
        Protocol.MAX,
        serverStore,
        server.epoch,
        2,
        server.store.changesSince(0, Int.MAX_VALUE).lastOrNull()?.first ?: 0,
    )

    override suspend fun pair(code: String, deviceName: String, platform: String): PairResponse = error("not used")

    override suspend fun push(ops: List<SyncOp>): PushResponse {
        if (dropRequest) throw IOException("request lost")
        val results = server.rules.push(ops)
        server.log += ops
        if (dropResponse) throw IOException("response lost")
        return PushResponse(results)
    }

    override suspend fun pull(cursor: Long, limit: Int): PullResponse = server.rules.pull(cursor, limit)

    override suspend fun revisions(entryId: String): List<RevisionInfo> = error("not used")

    override suspend fun revision(id: String): RevisionRecord = error("not used")

    override suspend fun reconcile(entries: List<KnownEntry>): ReconcileResponse = server.rules.reconcile(entries)

    override suspend fun startUpload(file: UploadStart): UploadStatus {
        uploadsRefused?.let { throw it }
        server.blobs[file.sha256]?.let { return UploadStatus(null, it.size.toLong(), complete = true) }
        val open = server.uploads.entries.firstOrNull { it.value.first.sha256 == file.sha256 }
        if (open != null) return UploadStatus(open.key, open.value.second.size().toLong(), complete = false)
        val id = "u${server.uploads.size + 1}"
        server.uploads[id] = file to ByteArrayOutputStream()
        return UploadStatus(id, 0, complete = false)
    }

    override suspend fun uploadChunk(id: String, offset: Long, data: ByteArray): UploadStatus {
        if (failAfterChunks == chunksAccepted) throw IOException("connection reset")
        val (file, bytes) = server.uploads.getValue(id)
        if (offset != bytes.size().toLong()) return UploadStatus(id, bytes.size().toLong(), complete = false)
        bytes.write(data)
        chunksAccepted++
        if (bytes.size().toLong() < file.size) return UploadStatus(id, bytes.size().toLong(), complete = false)
        server.uploads.remove(id)
        if (sha256(bytes.toByteArray()) != file.sha256) throw ChecksumMismatchException()
        server.blobs[file.sha256] = bytes.toByteArray()
        return UploadStatus(id, file.size, complete = true)
    }

    override suspend fun downloadBlob(sha256: String, target: File): String {
        val bytes = server.blobs[sha256] ?: throw ApiException(404, "not_found", "No such file")
        target.writeBytes(if (corruptDownloads) bytes.copyOf().also { it[0] = (it[0] + 1).toByte() } else bytes)
        return "image/jpeg"
    }
}

internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
