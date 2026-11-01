package app.benbow.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.request.ImageRequest
import java.io.File

/**
 * Coil with the platform's animated decoder: a GIF (or an animated WebP) moves wherever it is shown whole; not [playing],
 * its first frame stands still (decoded as a plain bitmap).
 */
@Composable
internal actual fun MovingPicture(file: File, modifier: Modifier, contentScale: ContentScale, playing: Boolean) {
    if (playing) {
        AsyncImage(file, null, modifier, contentScale = contentScale)
    } else {
        val context = LocalContext.current
        val still = remember(file) { ImageRequest.Builder(context).data(file).decoderFactory(BitmapFactoryDecoder.Factory()).build() }
        AsyncImage(still, null, modifier, contentScale = contentScale)
    }
}

/** The app's one image loader, before anything is drawn: Coil's own, plus moving GIFs. */
fun installAndroidImages(context: Context) {
    SingletonImageLoader.setSafe { ImageLoader.Builder(context).components { add(AnimatedImageDecoder.Factory()) }.build() }
}
