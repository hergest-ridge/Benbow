@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.app

import app.benbow.client.BlobSource
import app.benbow.client.LocalFile
import app.benbow.client.readBytes
import app.benbow.client.toByteArray
import app.benbow.client.toUint8Array
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import kotlinx.coroutines.await
import okio.Buffer
import kotlin.js.Promise

// Media in the browser (ADR-029): attaching what the user picks, drops or pastes, and showing pictures from the media
// store's blobs.

/** Attaches a file the browser handed over the way the apps attach a picked one: stored first, then added to the entry. */
internal fun AppServices.attachBrowserFile(entryId: suspend () -> String, file: JsAny) {
    val type = refineMime(fileType(file), fileName(file))
    attach(entryId, { BlobSource(file) }) {
        val kind = kindOf(type)
        val details = probeMedia(file, kind).await<JsAny>()
        MediaProbe(
            mime = type,
            name = fileName(file).ifEmpty { null },
            kind = kind,
            width = probeWidth(details).takeIf { it > 0 },
            height = probeHeight(details).takeIf { it > 0 },
            durationMs = probeDuration(details).toLong().takeIf { it > 0 },
            capturedAt = fileModified(file).toLong().takeIf { it > 0 },
            preview = probePreview(details)?.toByteArray(),
        )
    }
}

internal actual suspend fun coverJpeg(image: ByteArray): ByteArray? =
    scaledJpeg(image.toUint8Array(), COVER_SIZE).await<JsAny?>()?.toByteArray()

/** An image file's bytes drawn at most [max] px across and saved as a JPEG; null when the browser can't decode it. */
private fun scaledJpeg(bytes: JsAny, max: Int): Promise<JsAny?> = js(
    """(async () => {
        try {
            const bitmap = await createImageBitmap(new Blob([bytes]));
            const scale = Math.min(1, max / Math.max(bitmap.width, bitmap.height));
            const canvas = new OffscreenCanvas(Math.max(1, Math.round(bitmap.width * scale)), Math.max(1, Math.round(bitmap.height * scale)));
            canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
            bitmap.close();
            const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.88 });
            return new Uint8Array(await blob.arrayBuffer());
        } catch (e) { console.warn('Benbow: no cover', e); return null; }
    })()""",
)

/** Pictures come from the media store's blobs; Coil decodes them (with Skia, like on desktop) and caches the result. */
fun installWebImages() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context).components {
            add(TurnedJpegDecoder.Factory()) // before Coil's own: portrait phone photos keep their shape
            add(Keyer<LocalFile> { file, _ -> file.name })
            add(
                Fetcher.Factory<LocalFile> { file, options, _ ->
                    Fetcher {
                        SourceFetchResult(
                            source = ImageSource(Buffer().write(file.readBytes()), options.fileSystem),
                            mimeType = null,
                            dataSource = DataSource.DISK,
                        )
                    }
                },
            )
        }.build()
    }
}
