package app.benbow.client

import app.benbow.client.db.BenbowDb
import kotlin.concurrent.Volatile

/** A paired server. The token is a bearer secret; it never leaves this device except to that server. */
data class Connection(
    val baseUrl: String,
    val fingerprint: String,
    val token: String,
    val storeId: String,
    val epoch: Long,
    val deviceId: String,
)

/**
 * Seals the device token at rest. Android installs a Keystore-backed box at startup; on desktop the
 * token stays as is (the local DB sits in the user's profile, protected by the OS account).
 */
interface SecretBox {
    fun seal(plain: String): String

    /** Null when the secret can't be opened any more (the key is gone); the device then has to pair again. */
    fun open(sealed: String): String?

    object Plain : SecretBox {
        override fun seal(plain: String) = plain

        override fun open(sealed: String) = sealed
    }

    companion object {
        @Volatile
        var current: SecretBox = Plain
    }
}

/** Small key-value settings in the local DB. */
class Settings(private val db: BenbowDb) {
    private val q get() = db.benbowQueries

    fun get(key: String): String? = q.getValue(key).executeAsOneOrNull()

    fun put(key: String, value: String) {
        q.putValue(key, value)
    }

    var connection: Connection?
        get() {
            val url = get(URL) ?: return null
            val token = SecretBox.current.open(get(TOKEN)!!).orEmpty() // empty: the server answers 401, the user re-pairs
            return Connection(url, get(FINGERPRINT)!!, token, get(STORE)!!, get(EPOCH)!!.toLong(), get(DEVICE)!!)
        }
        set(value) = db.transaction {
            if (value == null) {
                listOf(URL, FINGERPRINT, TOKEN, STORE, EPOCH, DEVICE, CURSOR).forEach(q::deleteValue)
            } else {
                put(URL, value.baseUrl)
                put(FINGERPRINT, value.fingerprint)
                put(TOKEN, SecretBox.current.seal(value.token))
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
