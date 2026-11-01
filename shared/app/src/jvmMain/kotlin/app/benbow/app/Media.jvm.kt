package app.benbow.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.media_file
import app.benbow.app.resources.media_open
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

@Composable
internal actual fun MediaBar(
    services: AppServices,
    entryId: suspend () -> String,
    dictation: DictationTarget?,
    modifier: Modifier,
    tools: @Composable RowScope.() -> Unit,
) {
    val title = stringResource(Res.string.media_file)
    Row(modifier.padding(horizontal = 4.dp)) {
        IconButton({
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
                isMultipleMode = true
                isVisible = true // modal
            }
            dialog.files.forEach { file -> services.attach(entryId, { file.inputStream() }) { desktopProbe(file) } }
        }, modifier = Modifier.hand()) { Icon(Icons.Attach, title) }
        tools()
    }
}

/** No built-in player on desktop yet: hand the file to the system's player under a name with an extension. */
@Composable
internal actual fun MediaPlayer(file: File, mime: String, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button({
            val ext = mime.substringAfter('/').substringBefore(';').let { if (it == "mpeg") "mp3" else it }
            val copy = File(System.getProperty("java.io.tmpdir"), "benbow-${file.name.take(12)}.$ext")
            if (!copy.exists()) file.copyTo(copy)
            runCatching { Desktop.getDesktop().open(copy) }
        }, modifier = Modifier.hand()) { Text(stringResource(Res.string.media_open)) }
    }
}

/** The desktop has no player of its own: a video opens outside the app, and in a post shows its picture and ▶. */
internal actual val inlineVideos: Boolean = false

@Composable
internal actual fun InlineVideo(file: File, crop: Boolean, modifier: Modifier) = Unit

internal fun desktopProbe(file: File): MediaProbe {
    val mime = refineMime(runCatching { Files.probeContentType(file.toPath()) }.getOrNull(), file.name)
    val kind = kindOf(mime)
    val image = if (kind == "image") runCatching { ImageIO.read(file) }.getOrNull() else null
    return MediaProbe(
        mime = mime,
        name = file.name,
        kind = kind,
        width = image?.width,
        height = image?.height,
        capturedAt = file.lastModified(),
        preview = image?.let(::jpegPreview),
    )
}

internal actual suspend fun coverJpeg(image: ByteArray, max: Int): ByteArray? = withContext(Dispatchers.IO) {
    runCatching { ImageIO.read(ByteArrayInputStream(image)) }.getOrNull()?.let { jpegPreview(it, max) }
}

private fun jpegPreview(image: BufferedImage, max: Int = 512): ByteArray {
    val scale = minOf(1.0, max.toDouble() / maxOf(image.width, image.height))
    val w = (image.width * scale).toInt().coerceAtLeast(1)
    val h = (image.height * scale).toInt().coerceAtLeast(1)
    val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB) // JPEG has no alpha
    small.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        drawImage(image, 0, 0, w, h, null)
        dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(small, "jpg", it) }.toByteArray()
}

/** No dictation on desktop: no recognizer there (ADR-025). */
@Composable
internal actual fun DictationKey(services: AppServices, target: DictationTarget, modifier: Modifier) = Unit
