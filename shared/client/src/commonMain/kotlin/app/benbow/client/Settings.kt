package app.benbow.client

import app.benbow.client.db.BenbowDb

/** A paired server. The token is a bearer secret; it never leaves this device except to that server. */
data class Connection(
    val baseUrl: String,
    val fingerprint: String,
    val token: String,
    val storeId: String,
    val epoch: Long,
    val deviceId: String,
)

/** Small key-value settings in the local DB. TODO: keep the token in Android Keystore-backed storage. */
class Settings(private val db: BenbowDb) {
    private val q get() = db.benbowQueries

    fun get(key: String): String? = q.getValue(key).executeAsOneOrNull()

    fun put(key: String, value: String) {
        q.putValue(key, value)
    }

    var connection: Connection?
        get() {
            val url = get(URL) ?: return null
            return Connection(url, get(FINGERPRINT)!!, get(TOKEN)!!, get(STORE)!!, get(EPOCH)!!.toLong(), get(DEVICE)!!)
        }
        set(value) = db.transaction {
            if (value == null) {
                listOf(URL, FINGERPRINT, TOKEN, STORE, EPOCH, DEVICE, CURSOR).forEach(q::deleteValue)
            } else {
                put(URL, value.baseUrl)
                put(FINGERPRINT, value.fingerprint)
                put(TOKEN, value.token)
                put(STORE, value.storeId)
                put(EPOCH, value.epoch.toString())
                put(DEVICE, value.deviceId)
                put(CURSOR, "0")
            }
        }

    var cursor: Long
        get() = get(CURSOR)?.toLongOrNull() ?: 0
        set(value) = put(CURSOR, value.toString())

    companion object {
        private const val URL = "server.url"
        private const val FINGERPRINT = "server.fingerprint"
        private const val TOKEN = "server.token"
        private const val STORE = "server.store_id"
        private const val EPOCH = "server.epoch"
        private const val DEVICE = "device.id"
        private const val CURSOR = "sync.cursor"
        internal const val HLC = "sync.hlc"
    }
}
