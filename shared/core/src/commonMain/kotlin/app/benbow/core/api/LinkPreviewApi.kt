package app.benbow.core.api

import kotlinx.serialization.Serializable

/** `POST /api/v1/links/preview` (ADR-038): how the web client reads a page for its card (ADR-030 keeps it off the web). */
@Serializable
data class LinkPreviewRequest(val url: String)

/** What the page said; [image] is the SHA-256 of the JPEG the server keeps as a blob, fetched like any other. */
@Serializable
data class LinkPreviewInfo(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val site: String? = null,
    val image: String? = null,
)
