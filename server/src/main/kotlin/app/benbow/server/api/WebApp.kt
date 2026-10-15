package app.benbow.server.api

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.http.content.CompressedFileType
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
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
        }
    }
}

private val HASHED = Regex("[0-9a-f]{16,}\\.\\w+")
private val FOREVER = listOf(CacheControl.MaxAge(365 * 24 * 3600, visibility = CacheControl.Visibility.Public))
private val REVALIDATE = listOf(CacheControl.NoCache(null))
