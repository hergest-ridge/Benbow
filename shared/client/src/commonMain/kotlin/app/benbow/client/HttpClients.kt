package app.benbow.client

import io.ktor.client.HttpClient

/** How the client trusts the server's self-signed certificate (ADR-012). */
sealed interface ServerTrust {
    /** Normal operation: only the certificate with this SHA-256 fingerprint (`AA:BB:...`) is accepted. */
    data class Pinned(val fingerprint: String) : ServerTrust

    /**
     * First contact before pairing (trust on first use): any certificate is accepted and its
     * fingerprint reported, so the user can compare it with the server's admin page.
     */
    class FirstContact(val onCertificate: (fingerprint: String) -> Unit) : ServerTrust
}

/** HTTP client with JSON and the given trust policy. */
expect fun createHttpClient(trust: ServerTrust): HttpClient
