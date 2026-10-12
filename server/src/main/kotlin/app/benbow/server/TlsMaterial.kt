package app.benbow.server

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import javax.security.auth.x500.X500Principal
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream

/**
 * Self-signed certificate created on first run (ADR-012). Clients pin [fingerprint] from pairing,
 * so host names in the certificate don't matter for them; browsers show a one-time warning.
 */
class TlsMaterial(val keyStore: KeyStore, val fingerprint: String) {
    companion object {
        const val ALIAS = "benbow"

        fun ensure(dataDir: Path, password: String): TlsMaterial {
            val file = dataDir.resolve("tls").createDirectories().resolve("server.keystore")
            val keyStore = if (file.exists()) {
                KeyStore.getInstance(KeyStore.getDefaultType()).apply { file.inputStream().use { load(it, password.toCharArray()) } }
            } else {
                buildKeyStore {
                    certificate(ALIAS) {
                        this.password = password
                        subject = X500Principal("CN=Benbow Server")
                        daysValid = 3650
                        domains = listOf("localhost", InetAddress.getLocalHost().hostName)
                        ipAddresses = localAddresses()
                    }
                }.also { it.saveToFile(file.toFile(), password) }
            }
            val der = keyStore.getCertificate(ALIAS).encoded
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }
            return TlsMaterial(keyStore, fingerprint)
        }

        private fun localAddresses(): List<InetAddress> = buildList {
            add(InetAddress.getLoopbackAddress())
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { it.isSiteLocalAddress }
                .forEach(::add)
        }
    }
}
