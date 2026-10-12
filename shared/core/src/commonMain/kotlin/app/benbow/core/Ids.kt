package app.benbow.core

import kotlin.random.Random

/** UUIDv7 (RFC 9562): time-ordered ids that clients can create offline (docs/data-model.md). */
fun uuidV7(nowMillis: Long, random: Random = Random.Default): String {
    val bytes = random.nextBytes(16)
    for (i in 0 until 6) bytes[i] = (nowMillis ushr (8 * (5 - i))).toByte()
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
