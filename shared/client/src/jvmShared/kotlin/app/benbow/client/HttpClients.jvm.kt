package app.benbow.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

// Shared by the Android and desktop targets (src/jvmShared is a source dir of both).
actual fun createHttpClient(trust: ServerTrust): HttpClient {
    val trustManager = FingerprintTrustManager(trust)
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    val okHttp = OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, trustManager)
        // The certificate is pinned by fingerprint, so host names (LAN IPs change) are not checked.
        .hostnameVerifier { _, _ -> true }
        .build()
    return HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        install(ContentNegotiation) { json(ClientJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 30_000
        }
        expectSuccess = false
    }
}

private class FingerprintTrustManager(private val trust: ServerTrust) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        val fingerprint = fingerprintOf(leaf)
        when (trust) {
            is ServerTrust.Pinned -> if (!fingerprint.equals(trust.fingerprint, ignoreCase = true)) {
                throw CertificateException("Server certificate $fingerprint doesn't match the paired one; refusing to connect")
            }

            is ServerTrust.FirstContact -> trust.onCertificate(fingerprint)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) =
        throw CertificateException("Client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun fingerprintOf(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }
