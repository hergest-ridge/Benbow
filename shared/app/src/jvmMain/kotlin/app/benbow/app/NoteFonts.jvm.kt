package app.benbow.app

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import app.benbow.client.LocalFile

internal actual fun fontFromFile(file: LocalFile, weight: FontWeight): Font = androidx.compose.ui.text.platform.Font(file, weight)
