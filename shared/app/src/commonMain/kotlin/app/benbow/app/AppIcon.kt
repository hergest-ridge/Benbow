package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import app.benbow.app.resources.Res
import app.benbow.app.resources.app_icon
import org.jetbrains.compose.resources.painterResource

/** The app's icon (the inn's lantern over the night sea) as one square picture: the desktop window uses it. */
@Composable
fun appIcon(): Painter = painterResource(Res.drawable.app_icon)
