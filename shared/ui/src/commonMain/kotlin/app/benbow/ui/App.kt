package app.benbow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.benbow.ui.resources.Res
import app.benbow.ui.resources.home_greeting
import app.benbow.ui.resources.home_placeholder
import app.benbow.ui.resources.home_tagline
import app.benbow.ui.theme.BenbowTheme
import org.jetbrains.compose.resources.stringResource

/** Root composable shared by Android, desktop and web. */
@Composable
fun App() {
    BenbowTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(Res.string.home_greeting), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(Res.string.home_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(Res.string.home_placeholder), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Preview
@Composable
private fun AppPreview() = App()
