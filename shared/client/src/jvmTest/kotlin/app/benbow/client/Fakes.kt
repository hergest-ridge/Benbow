package app.benbow.client

import app.benbow.core.api.AndroidApp
import app.benbow.core.api.KnownEntry
import app.benbow.core.api.PairResponse
import app.benbow.core.api.Protocol
import app.benbow.core.api.PushResponse
import app.benbow.core.api.ReconcileResponse
import app.benbow.core.api.RevisionInfo
import app.benbow.core.api.ServerInfo
import app.benbow.core.api.SpeechModelInfo
import app.benbow.core.api.SpeechModelSpec
import app.benbow.core.api.UploadStart
import app.benbow.core.api.UploadStatus
import app.benbow.core.calendar.CalendarInfo
import app.benbow.core.calendar.CalendarOp
import app.benbow.core.calendar.CalendarProtocol
import app.benbow.core.calendar.CalendarPullResponse
import app.benbow.core.calendar.CalendarRules
import app.benbow.core.sync.PullResponse
import app.benbow.core.sync.RevisionRecord
import app.benbow.core.sync.SyncOp
import app.benbow.core.sync.SyncRules
import app.benbow.testing.InMemoryCalendarStore
import app.benbow.testing.InMemorySyncStore
import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

/** The server's rules over an in-memory store, plus a blob store; [log] lets a test take a "backup". */
internal class FakeServer(val storeId: String = "store-1", val epoch: Long = 1, val calendarEpoch: Long = 1) {
    val store = InMemorySyncStore()
    val rules = SyncRules(store)
    val log = mutableListOf<SyncOp>()

    /** The calendar store (ADR-027); off = a server older than 0.6.0. */
    var calendarOn = true
    val calendarStore = InMemoryCalendarStore()
    val calendarRules = CalendarRules(calendarStore)
    val calendarLog = mutableListOf<CalendarOp>()
    val blobs = mutableMapOf<String, ByteArray>()
    val uploads = mutableMapOf<String, Pair<UploadStart, ByteArrayOutputStream>>()

    /** The speech model's files as this server would hand them out (empty = not downloaded). */
    val speechFiles = mutableMapOf<String, ByteArray>()

    /** The Android app this server carries (ADR-042): its bytes and what `info` says of it. */
    var app: ByteArray? = null
    var appInfo: AndroidApp? = null

    /** A copy of this server as a backup would have it now (files included), restored under a new epoch. */
    fun restoredCopy(epoch: Long, withFiles: Boolean = true) = FakeServer(storeId, epoch, calendarEpoch).also {
        it.rules.push(log)
        it.log += log
        if (withFiles) it.blobs += blobs
        it.calendarRules.push(calendarLog)
        it.calendarLog += calendarLog
    }

    /** This server with only its calendar put back to [ops] (a calendar snapshot) under a new calendar epoch. */
    fun calendarRestoredTo(ops: List<CalendarOp>, calendarEpoch: Long) = FakeServer(storeId, epoch, calendarEpoch).also {
        it.rules.push(log)
        it.log += log
        it.blobs += blobs
        it.calendarRules.push(ops)
        it.calendarLog += ops
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

    /** Files whose chunks never get an answer (the server held their upload, 2026-10-19). */
    var stuck: Set<String> = emptySet()
    var chunksAccepted = 0

    override suspend fun info() = ServerInfo(
        "test",
        Protocol.MIN,
        Protocol.MAX,
        serverStore,
        server.epoch,
        2,
        server.store.changesSince(0, Int.MAX_VALUE).lastOrNull()?.first ?: 0,
        if (server.calendarOn) {
            CalendarInfo(
                CalendarProtocol.VERSION,
                server.calendarEpoch,
                server.calendarStore.changesSince(0, Int.MAX_VALUE).lastOrNull()?.seq ?: 0,
            )
        } else {
            null
        },
        server.appInfo,
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

    override suspend fun calendarPush(ops: List<CalendarOp>): PushResponse {
        if (dropRequest) throw IOException("request lost")
        val results = server.calendarRules.push(ops)
        server.calendarLog += ops
        if (dropResponse) throw IOException("response lost")
        return PushResponse(results)
    }

    override suspend fun calendarPull(cursor: Long, limit: Int): CalendarPullResponse = server.calendarRules.pull(cursor, limit)

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
        if (file.sha256 in stuck) throw IOException("Socket timeout has expired")
        if (offset != bytes.size().toLong()) return UploadStatus(id, bytes.size().toLong(), complete = false)
        bytes.write(data)
        chunksAccepted++
        if (bytes.size().toLong() < file.size) return UploadStatus(id, bytes.size().toLong(), complete = false)
        server.uploads.remove(id)
        if (sha256(bytes.toByteArray()) != file.sha256) throw ChecksumMismatchException()
        server.blobs[file.sha256] = bytes.toByteArray()
        return UploadStatus(id, file.size, complete = true)
    }

    override suspend fun downloadBlob(sha256: String, write: suspend (ByteReadChannel) -> Unit): String {
        val bytes = server.blobs[sha256] ?: throw ApiException(404, "not_found", "No such file")
        write(ByteReadChannel(if (corruptDownloads) bytes.copyOf().also { it[0] = (it[0] + 1).toByte() } else bytes))
        return "image/jpeg"
    }

    override suspend fun speechModel() = SpeechModelInfo(SpeechModelSpec.ID, server.speechFiles.isNotEmpty(), SpeechModelSpec.files)

    /** Serves at most [speechChunk] bytes per call, like a connection that keeps dropping. */
    override suspend fun downloadSpeechFile(name: String, from: Long, write: suspend (channel: ByteReadChannel, resumed: Boolean) -> Unit) {
        val bytes = server.speechFiles[name] ?: throw ApiException(404, "not_found", "No such model file here")
        val start = from.toInt()
        write(ByteReadChannel(bytes.copyOfRange(start, minOf(bytes.size, start + speechChunk))), start > 0)
    }

    var speechChunk = Int.MAX_VALUE

    /** The server's app (ADR-042), served at most [appChunk] bytes per call. */
    override suspend fun downloadApp(from: Long, write: suspend (channel: ByteReadChannel, resumed: Boolean) -> Unit) {
        val bytes = server.app ?: throw ApiException(404, "no_app", "This server carries no app")
        val start = from.toInt()
        write(ByteReadChannel(bytes.copyOfRange(start, minOf(bytes.size, start + appChunk))), start > 0)
    }

    var appChunk = Int.MAX_VALUE
}

internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
