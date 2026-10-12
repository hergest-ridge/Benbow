package app.benbow.server.storage

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class Device(val id: String, val name: String, val platform: String, val createdAt: Long, val lastSeenAt: Long?, val revokedAt: Long?)

/** Paired devices and their bearer tokens (docs/sync.md#pairing-and-security). Only token hashes are stored. */
class Devices(private val db: Database) {
    fun pair(name: String, platform: String): Pair<Device, String> {
        val token = randomToken()
        val device =
            Device(UUID.randomUUID().toString(), name.take(MAX_NAME), platform.take(MAX_NAME), System.currentTimeMillis(), null, null)
        db.transaction { c ->
            c.prepareStatement("INSERT INTO devices(id, name, platform, token_hash, created_at) VALUES (?, ?, ?, ?, ?)").use { st ->
                st.setString(1, device.id)
                st.setString(2, device.name)
                st.setString(3, device.platform)
                st.setString(4, hash(token))
                st.setLong(5, device.createdAt)
                st.executeUpdate()
            }
        }
        return device to token
    }

    /** The active (not revoked) device owning [token], or null. */
    fun authenticate(token: String): Device? = db.transaction { c ->
        val device =
            c.prepareStatement(
                "SELECT id, name, platform, created_at, last_seen_at, revoked_at FROM devices WHERE token_hash = ?",
            ).use { st ->
                st.setString(1, hash(token))
                st.executeQuery().use { rs -> if (rs.next()) rs.toDevice() else null }
            }?.takeIf { it.revokedAt == null } ?: return@transaction null
        c.prepareStatement("UPDATE devices SET last_seen_at = ? WHERE id = ?").use { st ->
            st.setLong(1, System.currentTimeMillis())
            st.setString(2, device.id)
            st.executeUpdate()
        }
        device
    }

    fun list(): List<Device> = db.locked { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT id, name, platform, created_at, last_seen_at, revoked_at FROM devices ORDER BY created_at").use { rs ->
                buildList { while (rs.next()) add(rs.toDevice()) }
            }
        }
    }

    fun revoke(id: String) = db.transaction { c ->
        c.prepareStatement("UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL").use { st ->
            st.setLong(1, System.currentTimeMillis())
            st.setString(2, id)
            st.executeUpdate()
        }
        Unit
    }

    private fun java.sql.ResultSet.toDevice() = Device(
        id = getString(1),
        name = getString(2),
        platform = getString(3),
        createdAt = getLong(4),
        lastSeenAt = getLong(5).takeIf { !wasNull() },
        revokedAt = getLong(6).takeIf { !wasNull() },
    )

    private companion object {
        const val MAX_NAME = 80

        fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

        fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
    }
}

/**
 * One-time pairing code shown on the admin page (localhost only). Single active code, short TTL,
 * and a few wrong guesses burn it, so a 6-digit code can't be brute-forced from the LAN.
 */
class PairingCodes(private val now: () -> Long = System::currentTimeMillis) {
    private var code: String? = null
    private var expiresAt = 0L
    private var failures = 0

    @Synchronized
    fun issue(): Pair<String, Long> {
        val fresh = (SecureRandom().nextInt(900_000) + 100_000).toString()
        code = fresh
        expiresAt = now() + TTL_MS
        failures = 0
        return fresh to expiresAt
    }

    @Synchronized
    fun active(): Pair<String, Long>? = code?.takeIf { now() < expiresAt }?.let { it to expiresAt }

    /** True exactly once for the right, unexpired code. */
    @Synchronized
    fun consume(candidate: String): Boolean {
        val current = code?.takeIf { now() < expiresAt } ?: return false
        if (MessageDigest.isEqual(current.toByteArray(), candidate.trim().toByteArray())) {
            code = null
            return true
        }
        if (++failures >= MAX_FAILURES) code = null
        return false
    }

    private companion object {
        const val TTL_MS = 5 * 60_000L
        const val MAX_FAILURES = 5
    }
}
