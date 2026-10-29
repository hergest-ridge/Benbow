package app.benbow.app

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import app.benbow.client.LocalFile

/** The browser keeps a font's bytes with it (BrowserFontShelf): Skia reads them as they are. */
internal actual fun fontFromFile(file: LocalFile, weight: FontWeight): Font =
    androidx.compose.ui.text.platform.Font("${file.name}-${weight.weight}", checkNotNull(file.bytes) { "a font without its bytes" }, weight)
