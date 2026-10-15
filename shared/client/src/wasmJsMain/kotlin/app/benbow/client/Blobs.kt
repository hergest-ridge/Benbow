@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.client

import kotlinx.coroutines.await
import kotlin.js.Promise

// Blobs: how the browser holds files (ADR-029). IndexedDB stores them as they are and the browser keeps their data on
// disk, so a large video is read into memory only in slices (upload) or never (playback through a blob: URL).

/** The file's content; pictures are decoded from it. */
suspend fun LocalFile.readBytes(): ByteArray = bytes ?: readBlob(blob)

/** A Blob's (or File's) whole content. */
suspend fun readBlob(blob: JsAny): ByteArray = blobBytes(blob).await<JsAny>().toByteArray()

internal fun blobOf(bytes: JsAny, mime: String): JsAny = js("new Blob([bytes], { type: mime })")

internal fun blobSize(blob: JsAny): Double = js("blob.size")

internal fun blobType(blob: JsAny): String = js("blob.type || ''")

internal fun objectUrl(blob: JsAny): String = js("URL.createObjectURL(blob)")

/** SHA-256 in hex, by the browser's own crypto (native speed, the file read once). */
internal fun blobSha256(blob: JsAny): Promise<JsString> = js(
    """blob.arrayBuffer().then(b => crypto.subtle.digest('SHA-256', b))
        .then(d => Array.from(new Uint8Array(d), x => x.toString(16).padStart(2, '0')).join(''))""",
)

internal fun blobBytes(blob: JsAny): Promise<JsAny> = js("blob.arrayBuffer().then(b => new Uint8Array(b))")

internal fun blobRange(blob: JsAny, from: Double, to: Double): Promise<JsAny> =
    js("blob.slice(from, to).arrayBuffer().then(b => new Uint8Array(b))")

internal fun newParts(): JsAny = js("[]")

internal fun addPart(parts: JsAny, bytes: JsAny): Unit = js("{ parts.push(bytes); }")

internal fun blobOfParts(parts: JsAny, mime: String): JsAny = js("new Blob(parts, { type: mime })")
