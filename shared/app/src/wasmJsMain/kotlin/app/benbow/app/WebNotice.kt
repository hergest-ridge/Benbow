package app.benbow.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.web_open_elsewhere
import app.benbow.ui.theme.BenbowTheme
import org.jetbrains.compose.resources.stringResource

/** What the browser shows instead of the app when it can't open it here. */
object WebNotice {
    @Composable
    fun OpenElsewhere() = Notice(stringResource(Res.string.web_open_elsewhere))

    @Composable
    private fun Notice(text: String) = BenbowTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(text, Modifier.widthIn(max = 420.dp), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        }
    }
}
