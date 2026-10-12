package app.benbow.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.benbow.ui.editor.BenbowEditor
import app.benbow.ui.editor.EditorState
import app.benbow.ui.editor.EditorToolbar
import app.benbow.ui.editor.sampleDocument
import app.benbow.ui.resources.Res
import app.benbow.ui.resources.editor_spike_title
import app.benbow.ui.theme.BenbowTheme
import org.jetbrains.compose.resources.stringResource

/** Root composable shared by Android, desktop and web. For now it hosts the editor spike (roadmap Phase 1). */
@Composable
fun App() {
    BenbowTheme { EditorSpikeScreen() }
}

@Composable
private fun EditorSpikeScreen() {
    val state = remember { EditorState(sampleDocument()) }
    Scaffold(
        topBar = {
            Text(
                text = stringResource(Res.string.editor_spike_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            )
        },
        bottomBar = { EditorToolbar(state, Modifier.navigationBarsPadding().imePadding()) },
    ) { padding ->
        BenbowEditor(
            state = state,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .fillMaxWidth(),
        )
    }
}

@Preview
@Composable
private fun AppPreview() = App()
