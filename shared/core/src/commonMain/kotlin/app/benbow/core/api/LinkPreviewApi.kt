package app.benbow.core.api

import kotlinx.serialization.Serializable

/** `POST /api/v1/links/preview` (ADR-038): how the web client reads a page for its card (ADR-030 keeps it off the web). */
@Serializable
data class LinkPreviewRequest(val url: String)

/**
 * What the page said; [image] is the SHA-256 of the JPEG the server keeps as a blob, fetched like any other. For an
 * address that is a picture (or a GIF site's page) [image] is that picture as the site gave it, a [mime] file of [width]
 * by [height] pixels, which the web client attaches under the link (ADR-045).
 */
@Serializable
data class LinkPreviewInfo(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val site: String? = null,
    val image: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val mime: String? = null,
)
