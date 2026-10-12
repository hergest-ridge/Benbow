package app.benbow.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Sync protocol range this build speaks (docs/sync.md#api-versioning-and-docs). */
object Protocol {
    const val MIN = 1
    const val MAX = 1
}

/** Response of `GET /api/v1/info`. Store identity fields join once the server has storage. */
@Serializable
data class ServerInfo(
    @SerialName("server_version") val serverVersion: String,
    @SerialName("protocol_min") val protocolMin: Int,
    @SerialName("protocol_max") val protocolMax: Int,
)
