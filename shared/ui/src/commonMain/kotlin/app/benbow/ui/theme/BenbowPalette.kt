package app.benbow.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Theme tokens for text color, highlight and callout tones (docs/content-format.md#theme-tokens).
 * Unknown tokens resolve to null so callers fall back to the default color.
 */
object BenbowPalette {
    val tokens = listOf("gray", "brown", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "red")

    private val light = mapOf(
        "gray" to Color(0xFF787774), "brown" to Color(0xFF9F6B53), "orange" to Color(0xFFD9730D),
        "yellow" to Color(0xFFB7871F), "green" to Color(0xFF448361), "teal" to Color(0xFF2E7C7C),
        "blue" to Color(0xFF337EA9), "purple" to Color(0xFF9065B0), "pink" to Color(0xFFC14C8A),
        "red" to Color(0xFFD44C47),
    )

    private val dark = mapOf(
        "gray" to Color(0xFFA5A29E), "brown" to Color(0xFFC9967E), "orange" to Color(0xFFFFA344),
        "yellow" to Color(0xFFFFD45E), "green" to Color(0xFF6CC08F), "teal" to Color(0xFF5FC4C4),
        "blue" to Color(0xFF6CB4E4), "purple" to Color(0xFFB794E0), "pink" to Color(0xFFEA7DB7),
        "red" to Color(0xFFFF7B72),
    )

    fun text(token: String, dark: Boolean): Color? = (if (dark) this.dark else light)[token]

    fun highlight(token: String, dark: Boolean): Color? = text(token, dark)?.copy(alpha = if (dark) 0.32f else 0.22f)

    fun tint(token: String, dark: Boolean): Color? = text(token, dark)?.copy(alpha = if (dark) 0.18f else 0.12f)
}
