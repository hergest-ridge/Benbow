package app.benbow.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.benbow.app.resources.Res
import app.benbow.app.resources.meddon
import org.jetbrains.compose.resources.Font

/**
 * "Benbow" in Meddon, Vernon Adams' face after 18th-century English handwriting (the Treasure Island years; OFL,
 * docs/licenses/Meddon-OFL.txt). Only the name uses it; everything else stays in the UI font.
 */
@Composable
internal fun Wordmark(modifier: Modifier = Modifier) {
    Text(
        "Benbow",
        fontFamily = FontFamily(Font(Res.font.meddon)),
        fontSize = 44.sp,
        lineHeight = 72.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}
