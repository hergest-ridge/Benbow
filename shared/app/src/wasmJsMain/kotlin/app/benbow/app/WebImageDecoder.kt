@file:OptIn(ExperimentalWasmJsInterop::class, ExperimentalCoilApi::class)

package app.benbow.app

import app.benbow.client.toByteArray
import app.benbow.client.toUint8Array
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.size.Precision
import kotlinx.coroutines.await
import okio.use
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.js.Promise

/**
 * A phone usually saves a portrait photo as a landscape JPEG with an EXIF note to turn it. Coil in the browser sizes the
 * picture from the JPEG header (landscape) while the browser's decoder turns it, so it came out squeezed into the wrong
 * shape. Such photos (EXIF orientation 2–8) are decoded here instead: at the turned size, by the browser itself, off
 * the main thread. Every other picture keeps Coil's own path.
 */
internal class TurnedJpegDecoder(private val result: SourceFetchResult, private val orientation: Int, private val options: Options) :
    Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = result.source.source().use { it.readByteArray() }
        val (headerWidth, headerHeight) = jpegSize(bytes) ?: error("Not a JPEG")
        val turned = orientation >= 5 // 5–8 swap width and height
        val width = if (turned) headerHeight else headerWidth
        val height = if (turned) headerWidth else headerHeight
        val dst = DecodeUtils.computeDstSize(width, height, options.size, options.scale, options.maxBitmapSize)
        var multiplier = DecodeUtils.computeSizeMultiplier(
            width.toDouble(),
            height.toDouble(),
            dst.first.toDouble(),
            dst.second.toDouble(),
            options.scale,
            options.maxBitmapSize,
        )
        if (options.precision == Precision.INEXACT) multiplier = multiplier.coerceAtMost(1.0)
        val outWidth = (multiplier * width).toInt().coerceAtLeast(1)
        val outHeight = (multiplier * height).toInt().coerceAtLeast(1)
        val pixels = decodeTurned(bytes.toUint8Array(), outWidth, outHeight).await<JsAny>().toByteArray()
        val info = ImageInfo(ColorInfo(ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB), outWidth, outHeight)
        val bitmap = Bitmap()
        check(bitmap.installPixels(info, pixels, info.minRowBytes)) { "Couldn't take the decoded picture" }
        bitmap.setImmutable()
        return DecodeResult(bitmap.asImage(), isSampled = outWidth < width || outHeight < height)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val head = result.source.source().peek().use { peek ->
                peek.request(HEAD_BYTES)
                peek.readByteArray(minOf(HEAD_BYTES, peek.buffer.size))
            }
            val orientation = exifOrientation(head)
            return if (orientation in 2..8) TurnedJpegDecoder(result, orientation, options) else null
        }
    }

    private companion object {
        const val HEAD_BYTES = 128L * 1024
    }
}

/** A JPEG's width and height as stored (before any EXIF turn), from its frame header. */
internal fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 4 || bytes.u8(0) != 0xFF || bytes.u8(1) != 0xD8) return null
    var at = 2
    while (at + 9 < bytes.size) {
        if (bytes.u8(at) != 0xFF) return null
        val marker = bytes.u8(at + 1)
        val length = bytes.u16be(at + 2)
        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
            return bytes.u16be(at + 7) to bytes.u16be(at + 5)
        }
        at += 2 + length
    }
    return null
}

/** The EXIF orientation (1–8) of a JPEG, or 1 when it has none. */
internal fun exifOrientation(bytes: ByteArray): Int {
    if (bytes.size < 4 || bytes.u8(0) != 0xFF || bytes.u8(1) != 0xD8) return 1
    var at = 2
    while (at + 4 < bytes.size && bytes.u8(at) == 0xFF) {
        val marker = bytes.u8(at + 1)
        val length = bytes.u16be(at + 2)
        if (marker == 0xDA) return 1 // the picture data starts: no EXIF before it
        if (marker == 0xE1 && at + 10 < bytes.size && bytes.decodeToString(at + 4, at + 8) == "Exif") {
            val tiff = at + 10
            if (tiff + 8 > bytes.size) return 1
            val little = bytes.u8(tiff) == 0x49 // "II"
            fun u16(p: Int) = if (little) bytes.u8(p) or (bytes.u8(p + 1) shl 8) else bytes.u16be(p)
            fun u32(p: Int) = if (little) u16(p) or (u16(p + 2) shl 16) else (bytes.u16be(p) shl 16) or bytes.u16be(p + 2)
            val ifd = tiff + u32(tiff + 4)
            if (ifd + 2 > bytes.size) return 1
            for (i in 0 until u16(ifd)) {
                val entry = ifd + 2 + 12 * i
                if (entry + 12 > bytes.size) return 1
                if (u16(entry) == 0x0112) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
            }
            return 1
        }
        at += 2 + length
    }
    return 1
}

private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF

private fun ByteArray.u16be(at: Int) = (u8(at) shl 8) or u8(at + 1)

/** The browser decodes the photo turned as its EXIF says, at [width] × [height], and gives the RGBA pixels. */
private fun decodeTurned(bytes: JsAny, width: Int, height: Int): Promise<JsAny> = js(
    """(async () => {
        const bitmap = await createImageBitmap(new Blob([bytes]), {
            imageOrientation: 'from-image', resizeWidth: width, resizeHeight: height, resizeQuality: 'high' });
        try {
            const canvas = new OffscreenCanvas(width, height);
            const context = canvas.getContext('2d', { willReadFrequently: true });
            context.drawImage(bitmap, 0, 0);
            return new Uint8Array(context.getImageData(0, 0, width, height).data.buffer);
        } finally { bitmap.close(); }
    })()""",
)
