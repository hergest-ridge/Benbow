package app.benbow.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.benbow.ui.App

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Benbow") {
        App()
    }
}
