package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import java.io.File

/** A picture of many frames plays with Skia; any other shows through Coil as everywhere. */
@Composable
internal actual fun MovingPicture(file: File, modifier: Modifier, contentScale: ContentScale) {
    val frames by produceState<Codec?>(null, file) { value = withContext(Dispatchers.IO) { framesOf(file.readBytes()) } }
    val codec = frames
    if (codec != null) MovingFrames(codec, modifier, contentScale) else AsyncImage(file, null, modifier, contentScale = contentScale)
}
