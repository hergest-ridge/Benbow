package app.benbow.core.api

import app.benbow.core.calendar.CalendarInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Sync protocol range this build speaks (docs/sync.md#api-versioning-and-docs). */
object Protocol {
    /**
     * An app before 3 would show notes on the board in its journal (ADR-040), so it may no longer sync: it asks to be
     * updated and keeps its outbox until then.
     */
    const val MIN = 3

    /** 2: `entry.attachment.put` and attachments in entry state. 3: notes on the board (the `space` field, ADR-040). */
    const val MAX = 3
}

/** Response of `GET /api/v1/info`; store identity per docs/sync.md#store-identity. */
@Serializable
data class ServerInfo(
    @SerialName("server_version") val serverVersion: String,
    @SerialName("protocol_min") val protocolMin: Int,
    @SerialName("protocol_max") val protocolMax: Int,
    @SerialName("store_id") val storeId: String,
    val epoch: Long,
    @SerialName("schema_version") val schemaVersion: Int,
    /** Latest change-log sequence (server 0.3.0+). Lower than a client's cursor means the store went back in time. */
    val cursor: Long? = null,
    /** The calendar store, synced on its own channel (server 0.6.0+, ADR-027). */
    val calendar: CalendarInfo? = null,
)
