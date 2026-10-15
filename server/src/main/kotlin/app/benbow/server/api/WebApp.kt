package app.benbow.server.api

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.http.content.CompressedFileType
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The web client at `/app/` (ADR-029): the same app as on the phone, compiled to WebAssembly, from the package's `web`
 * folder. Files named by their content hash are cached for good; the rest are revalidated on each load (a 304 when
 * unchanged), so a server update reaches the browser on its next reload. Each file has a gzip twin made at packaging.
 */
fun Route.webApp(root: Path?) {
    get("/app") { call.respondRedirect("/app/") }
    if (root == null || !root.resolve("index.html").exists()) {
        get("/app/{path...}") {
            call.respondText(
                "This server package has no web client (build it with ./gradlew :server:webApp)",
                ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                HttpStatusCode.NotFound,
            )
        }
        return
    }
    route("/app") {
        install(ConditionalHeaders)
        staticFiles("/", root.toFile()) {
            preCompressed(CompressedFileType.GZIP)
            cacheControl { file -> if (HASHED.matches(file.name)) FOREVER else REVALIDATE }
            modify { _, call ->
                call.response.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                call.response.header("Referrer-Policy", "no-referrer")
                call.response.header("X-Content-Type-Options", "nosniff")
            }
        }
    }
}

/**
 * The page talks to this server and nothing else: no fonts, scripts or requests from elsewhere, whatever a library
 * might try (Compose for Web fetches missing glyphs from Google's font CDN; the app bundles its emoji instead).
 * WebAssembly needs 'wasm-unsafe-eval'; pictures and players use blob: URLs, and Coil decodes pictures in a worker it
 * starts from a blob: URL; the loading screen has inline styles.
 */
internal const val CONTENT_SECURITY_POLICY =
    "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; " +
        "media-src 'self' blob:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; object-src 'none'; base-uri 'self'; " +
        "frame-ancestors 'none'; form-action 'self'"

private val HASHED = Regex("[0-9a-f]{16,}\\.\\w+")
private val FOREVER = listOf(CacheControl.MaxAge(365 * 24 * 3600, visibility = CacheControl.Visibility.Public))
private val REVALIDATE = listOf(CacheControl.NoCache(null))
