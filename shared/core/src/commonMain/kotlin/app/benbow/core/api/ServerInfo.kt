package app.benbow.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Sync protocol range this build speaks (docs/sync.md#api-versioning-and-docs). */
object Protocol {
    const val MIN = 1

    /** 2: `entry.attachment.put` and attachments in entry state. */
    const val MAX = 2
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
)
