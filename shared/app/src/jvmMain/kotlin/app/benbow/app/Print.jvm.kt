package app.benbow.app

import androidx.compose.runtime.Composable
import java.awt.Desktop
import java.io.File

/** Desktop has no print dialog of its own here: the page opens in the default browser, where Ctrl+P prints or saves a PDF. */
@Composable
internal actual fun rememberPrint(): (PrintPage) -> Unit = { page ->
    val dir = File(System.getProperty("java.io.tmpdir"), "benbow-print").apply { mkdirs() }
    val name = page.title.replace(Regex("""[\\/:*?"<>|\s]+"""), " ").trim().take(60).ifEmpty { "entry" }
    val file = File(dir, "$name.html").apply { writeText(page.html) }
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(file.toURI())
}
