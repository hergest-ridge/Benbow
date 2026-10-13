package app.benbow.app

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * Paints the native Windows title bar in the app's colors through DWM, instead of drawing our own: snap layouts on the
 * maximize button, the shadow, rounded corners and resizing by the edges all stay Windows' own. Windows 11 takes the
 * exact caption and text colors; Windows 10 (20H1+) only switches to its dark caption. Elsewhere it does nothing.
 */
internal object WindowsTitleBar {
    @Suppress("FunctionName")
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private const val USE_IMMERSIVE_DARK_MODE = 20
    private const val CAPTION_COLOR = 35
    private const val TEXT_COLOR = 36

    private val dwm: Dwm? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return@lazy null
        runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()
    }

    /** True once the window had a handle to paint; the caller tries again until then. */
    fun paint(window: ComposeWindow, caption: Color, text: Color, dark: Boolean): Boolean {
        val api = dwm ?: return true
        val handle = window.windowHandle
        if (handle == 0L) return false
        val hwnd = Pointer(handle)
        fun set(attribute: Int, value: Int) = runCatching { api.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4) }
        set(USE_IMMERSIVE_DARK_MODE, if (dark) 1 else 0)
        set(CAPTION_COLOR, caption.colorRef())
        set(TEXT_COLOR, text.colorRef())
        return true
    }

    /** Win32 COLORREF: 0x00BBGGRR. */
    private fun Color.colorRef(): Int {
        val argb = toArgb()
        return (argb and 0xFF shl 16) or (argb and 0xFF00) or (argb shr 16 and 0xFF)
    }
}
