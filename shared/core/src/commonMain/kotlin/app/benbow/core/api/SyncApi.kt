package app.benbow.core.api

import app.benbow.core.sync.OpResult
import app.benbow.core.sync.SyncOp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /api/v1/pair` (no auth): exchanges the one-time code from the admin page for a device token. */
@Serializable
data class PairRequest(val code: String, @SerialName("device_name") val deviceName: String, val platform: String)

@Serializable
data class PairResponse(
    @SerialName("device_id") val deviceId: String,
    val token: String,
    @SerialName("store_id") val storeId: String,
    val epoch: Long,
)

/** `POST /api/v1/sync/push`: ops applied in order, one transaction each (docs/sync.md#push). */
@Serializable
data class PushRequest(val protocol: Int, val ops: List<SyncOp>)

@Serializable
data class PushResponse(val results: List<OpResult>)

/**
 * `POST /api/v1/sync/reconcile`, after a server restore (docs/sync.md#store-identity): the client lists
 * the entries and head revisions it knows, the server answers with what it lacks, the client re-pushes.
 */
@Serializable
data class ReconcileRequest(val entries: List<KnownEntry>)

@Serializable
data class KnownEntry(val id: String, val head: String?)

@Serializable
data class ReconcileResponse(
    @SerialName("missing_entries") val missingEntries: List<String>,
    @SerialName("missing_revisions") val missingRevisions: List<String>,
)

@Serializable
data class ApiError(val error: String, val message: String)

/** One item of `GET /api/v1/entries/{id}/revisions` (newest first, no body). */
@Serializable
data class RevisionInfo(val id: String, val parents: List<String>, val origin: String, val createdAt: Long, val bodySha256: String)
