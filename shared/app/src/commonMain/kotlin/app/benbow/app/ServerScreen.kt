package app.benbow.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.retry
import app.benbow.app.resources.server
import app.benbow.app.resources.server_cancel
import app.benbow.app.resources.server_code
import app.benbow.app.resources.server_connect
import app.benbow.app.resources.server_connecting
import app.benbow.app.resources.server_device_name
import app.benbow.app.resources.server_error
import app.benbow.app.resources.server_fingerprint
import app.benbow.app.resources.server_fingerprint_check
import app.benbow.app.resources.server_forget
import app.benbow.app.resources.server_forget_blocked
import app.benbow.app.resources.server_forget_confirm
import app.benbow.app.resources.server_host
import app.benbow.app.resources.server_incompatible
import app.benbow.app.resources.server_intro
import app.benbow.app.resources.server_last_sync
import app.benbow.app.resources.server_never
import app.benbow.app.resources.server_pair
import app.benbow.app.resources.server_paired
import app.benbow.app.resources.server_port
import app.benbow.app.resources.server_rejected_hint
import app.benbow.app.resources.server_rejected_title
import app.benbow.app.resources.server_sync_now
import app.benbow.app.resources.server_version
import app.benbow.app.resources.server_wrong_store
import app.benbow.client.Connection
import app.benbow.client.Probe
import app.benbow.client.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

internal class ServerViewModel(private val services: AppServices) : ViewModel() {
    var connection by mutableStateOf<Connection?>(services.settings.connection)
        private set
    var host by mutableStateOf("")
    var port by mutableStateOf("8765")
    var code by mutableStateOf("")
    var deviceName by mutableStateOf(services.deviceName)
    var probe by mutableStateOf<Probe?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun connect() {
        val portNumber = port.trim().toIntOrNull() ?: return
        run { probe = services.pairing.probe(host.trim(), portNumber) }
    }

    fun pair() {
        val first = probe ?: return
        run {
            connection = services.pairing.pair(first, code.trim(), deviceName.trim(), services.platform)
            probe = null
            code = ""
            services.sync.requestSync()
        }
    }

    fun cancel() {
        probe = null
        error = null
    }

    fun forget() = run {
        withContext(Dispatchers.IO) { services.pairing.forget() }
        connection = null
    }

    fun retryRejected() {
        services.scope.launch {
            services.repo.retryRejected()
            services.sync.requestSync()
        }
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerScreen(services: AppServices, nav: Navigator) {
    val vm = viewModel { ServerViewModel(services) }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text(stringResource(Res.string.server)) })
    }) { padding ->
        Column(
            Modifier.padding(
                padding,
            ).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val connection = vm.connection
            when {
                connection != null -> Paired(services, vm, connection)
                vm.probe != null -> ConfirmServer(vm, vm.probe!!)
                else -> FindServer(vm)
            }
            vm.error?.let { Text(stringResource(Res.string.server_error, it), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun FindServer(vm: ServerViewModel) {
    Text(stringResource(Res.string.server_intro), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        vm.host,
        { vm.host = it },
        label = { Text(stringResource(Res.string.server_host)) },
        placeholder = { Text("192.168.1.10") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        vm.port,
        { vm.port = it.filter(Char::isDigit) },
        label = { Text(stringResource(Res.string.server_port)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(vm::connect, enabled = vm.host.isNotBlank() && vm.port.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (vm.busy) Res.string.server_connecting else Res.string.server_connect))
    }
}

@Composable
private fun ConfirmServer(vm: ServerViewModel, probe: Probe) {
    Text(stringResource(Res.string.server_version, probe.info.serverVersion), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(Res.string.server_fingerprint_check), style = MaterialTheme.typography.bodyLarge)
    Fingerprint(probe.fingerprint)
    OutlinedTextField(
        vm.code,
        { vm.code = it.filter(Char::isDigit).take(6) },
        label = { Text(stringResource(Res.string.server_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        vm.deviceName,
        { vm.deviceName = it },
        label = { Text(stringResource(Res.string.server_device_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(vm::cancel, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.server_cancel)) }
        Button(vm::pair, enabled = vm.code.length == 6 && vm.deviceName.isNotBlank() && !vm.busy, modifier = Modifier.weight(1f)) {
            Text(stringResource(Res.string.server_pair))
        }
    }
}

@Composable
private fun Paired(services: AppServices, vm: ServerViewModel, connection: Connection) {
    val status by services.sync.status.collectAsStateWithLifecycle()
    val rejected by services.repo.rejectedOps().collectAsStateWithLifecycle(emptyList())
    var confirmForget by remember { mutableStateOf(false) }

    Text(
        stringResource(Res.string.server_paired, connection.baseUrl.removePrefix("https://")),
        style = MaterialTheme.typography.titleMedium,
    )
    syncLabel(status)?.let { (label, tone) -> Text(label, color = tone, style = MaterialTheme.typography.bodyLarge) }
    Text(
        stringResource(
            Res.string.server_last_sync,
            status.lastSuccessAt?.let { dateTimeLabel(it) } ?: stringResource(Res.string.server_never),
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (val last = status.last) {
        is SyncResult.WrongServer -> Text(stringResource(Res.string.server_wrong_store), color = MaterialTheme.colorScheme.error)

        is SyncResult.Incompatible -> Text(stringResource(Res.string.server_incompatible), color = MaterialTheme.colorScheme.error)

        is SyncResult.Failed -> Text(
            stringResource(Res.string.server_error, last.reason),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> {}
    }
    Button(services.sync::requestSync, enabled = !status.syncing, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.server_sync_now))
    }

    if (rejected.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.server_rejected_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(Res.string.server_rejected_hint), style = MaterialTheme.typography.bodyMedium)
                rejected.forEach { Text("• ${it.error ?: it.opId}", style = MaterialTheme.typography.bodySmall) }
                TextButton(vm::retryRejected) { Text(stringResource(Res.string.retry)) }
            }
        }
    }

    Text(stringResource(Res.string.server_fingerprint), style = MaterialTheme.typography.titleSmall)
    Fingerprint(connection.fingerprint)

    val canForget = status.pending == 0L && status.rejected == 0L
    OutlinedButton({
        confirmForget = true
    }, enabled = canForget && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.server_forget)) }
    if (!canForget) {
        Text(
            stringResource(Res.string.server_forget_blocked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            text = { Text(stringResource(Res.string.server_forget_confirm)) },
            confirmButton = {
                TextButton({
                    confirmForget = false
                    vm.forget()
                }) { Text(stringResource(Res.string.server_forget)) }
            },
            dismissButton = { TextButton({ confirmForget = false }) { Text(stringResource(Res.string.server_cancel)) } },
        )
    }
}

@Composable
private fun Fingerprint(fingerprint: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            fingerprintLines(fingerprint),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}
