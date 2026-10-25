package app.benbow.app

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image

// The desktop and the browser (both Skia): Coil shows a GIF's first frame only there, so a picture of many frames is
// played here, each frame decoded into one bitmap on top of the one it builds on.

/** [bytes] as frames to play, when they are a picture of more than one frame (a GIF, an animated WebP); else null. */
internal fun framesOf(bytes: ByteArray): Codec? =
    runCatching { Codec.makeFromData(Data.makeFromBytes(bytes)) }.getOrNull()?.takeIf { it.frameCount > 1 }

@Composable
internal fun MovingFrames(codec: Codec, modifier: Modifier, contentScale: ContentScale) {
    var shown by remember(codec) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(codec) {
        val bitmap = Bitmap().apply { allocPixels(codec.imageInfo) }
        val frames = codec.framesInfo
        var i = 0
        while (true) {
            // The frame it builds on is in the bitmap already when it is the one before; otherwise Skia decodes it first.
            val required = frames[i].requiredFrame
            when (required) {
                -1 -> codec.readPixels(bitmap, i, -1)
                i - 1 -> codec.readPixels(bitmap, i, i - 1)
                else -> codec.readPixels(bitmap, i)
            }
            shown = Image.makeFromBitmap(bitmap).toComposeImageBitmap()
            delay(frames[i].duration.coerceAtLeast(MIN_FRAME_MS).toLong())
            i = (i + 1) % frames.size
        }
    }
    shown?.let { Image(it, null, modifier, contentScale = contentScale) }
}

/** Browsers play a GIF's frames of 0 or 10 ms at 100 ms; so does this. */
private const val MIN_FRAME_MS = 20
