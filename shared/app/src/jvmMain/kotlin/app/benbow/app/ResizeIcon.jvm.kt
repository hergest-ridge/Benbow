package app.benbow.app

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

internal actual val ResizeColumnIcon: PointerIcon = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

internal actual val ResizeRowIcon: PointerIcon = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))
