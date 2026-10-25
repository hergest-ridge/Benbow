package app.benbow.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import java.io.File

/** Coil with the platform's animated decoder: a GIF (or an animated WebP) moves wherever it is shown whole. */
@Composable
internal actual fun MovingPicture(file: File, modifier: Modifier, contentScale: ContentScale) {
    AsyncImage(file, null, modifier, contentScale = contentScale)
}

/** The app's one image loader, before anything is drawn: Coil's own, plus moving GIFs. */
fun installAndroidImages(context: Context) {
    SingletonImageLoader.setSafe { ImageLoader.Builder(context).components { add(AnimatedImageDecoder.Factory()) }.build() }
}
