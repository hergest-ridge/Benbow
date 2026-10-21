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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import app.benbow.app.resources.server_calendar_problem
import app.benbow.app.resources.server_calendar_unsupported
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
import app.benbow.app.resources.server_web_code
import app.benbow.app.resources.server_web_intro
import app.benbow.app.resources.server_wrong_store
import app.benbow.app.resources.storage
import app.benbow.app.resources.update_quiet
import app.benbow.app.resources.update_quiet_hint
import app.benbow.client.Connection
import app.benbow.client.IoDispatcher
import app.benbow.client.Probe
import app.benbow.client.SyncResult
import app.benbow.client.calendar.CalendarSyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** The server that served this page: the web client pairs only with it (ADR-029). Null in the apps. */
internal expect val pageServer: String?

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

    init {
        // The browser already knows its server: straight to the code.
        if (connection == null && pageServer != null) connect()
    }

    fun connect() {
        pageServer?.let { origin -> return run { probe = services.pairing.probeOrigin(origin) } }
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
        withContext(IoDispatcher) { services.forgetServer() }
        connection = null
    }

    fun retryRejected() {
        services.scope.launch {
            services.repo.retryRejected()
            services.calendar.retryRejected()
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
        TopAppBar(
            navigationIcon = { BackButton(nav::back) },
            title = { Text(stringResource(Res.string.server)) },
            actions = {
                // «Место» (ADR-041): what the files weigh there and here.
                if (vm.connection != null) {
                    IconButton({ nav.open(Route.Storage) }, Modifier.hand()) { Icon(Icons.Storage, stringResource(Res.string.storage)) }
                }
            },
        )
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
    pageServer?.let { origin ->
        Text(stringResource(Res.string.server_web_intro, origin), style = MaterialTheme.typography.bodyLarge)
        Button(vm::connect, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().hand(!vm.busy)) {
            Text(stringResource(if (vm.busy) Res.string.server_connecting else Res.string.server_connect))
        }
        return
    }
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
    Button(
        vm::connect,
        enabled = vm.host.isNotBlank() && vm.port.isNotBlank() && !vm.busy,
        modifier = Modifier.fillMaxWidth().hand(vm.host.isNotBlank() && vm.port.isNotBlank() && !vm.busy),
    ) {
        Text(stringResource(if (vm.busy) Res.string.server_connecting else Res.string.server_connect))
    }
}

@Composable
private fun ConfirmServer(vm: ServerViewModel, probe: Probe) {
    Text(stringResource(Res.string.server_version, probe.info.serverVersion), style = MaterialTheme.typography.titleMedium)
    if (probe.fingerprint.isEmpty()) {
        Text(stringResource(Res.string.server_web_code), style = MaterialTheme.typography.bodyLarge)
    } else {
        Text(stringResource(Res.string.server_fingerprint_check), style = MaterialTheme.typography.bodyLarge)
        Fingerprint(probe.fingerprint)
    }
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
        OutlinedButton(vm::cancel, enabled = !vm.busy, modifier = Modifier.weight(1f).hand(!vm.busy)) {
            Text(stringResource(Res.string.server_cancel))
        }
        Button(
            vm::pair,
            enabled = vm.code.length == 6 && vm.deviceName.isNotBlank() && !vm.busy,
            modifier = Modifier.weight(1f).hand(vm.code.length == 6 && vm.deviceName.isNotBlank() && !vm.busy),
        ) {
            Text(stringResource(Res.string.server_pair))
        }
    }
}

@Composable
private fun Paired(services: AppServices, vm: ServerViewModel, connection: Connection) {
    val status by services.sync.status.collectAsStateWithLifecycle()
    val rejectedEntries by services.repo.rejectedOps().collectAsStateWithLifecycle(emptyList())
    val rejectedEvents by services.calendar.rejectedOps().collectAsStateWithLifecycle(emptyList())
    val rejected = rejectedEntries + rejectedEvents
    val calendar by services.calendarStatus.collectAsStateWithLifecycle()
    var confirmForget by remember { mutableStateOf(false) }

    Text(
        stringResource(Res.string.server_paired, connection.baseUrl.substringAfter("://")),
        style = MaterialTheme.typography.titleMedium,
    )
    syncLook(status)?.let { Text(it.label, color = it.tone, style = MaterialTheme.typography.bodyLarge) }
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

        is SyncResult.Incompatible -> {
            Text(stringResource(Res.string.server_incompatible), color = MaterialTheme.colorScheme.error)
            // The app this server carries is the one that syncs with it (ADR-042): its update right here.
            updateLook(services)?.let { look ->
                TextButton(onClick = { look.action?.invoke() }, enabled = look.action != null) { Text("${look.title}: ${look.subtitle}") }
            }
        }

        is SyncResult.Failed -> Text(
            stringResource(Res.string.server_error, last.reason),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> {}
    }
    // The calendar syncs on its own channel (ADR-027); its trouble shows apart and never stops the journal.
    when (val last = calendar) {
        CalendarSyncResult.Unsupported -> Text(
            stringResource(Res.string.server_calendar_unsupported),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CalendarSyncResult.WrongServer -> Text(stringResource(Res.string.server_wrong_store), color = MaterialTheme.colorScheme.error)

        is CalendarSyncResult.Incompatible -> Text(stringResource(Res.string.server_incompatible), color = MaterialTheme.colorScheme.error)

        is CalendarSyncResult.Failed -> Text(
            stringResource(Res.string.server_calendar_problem, last.reason),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> {}
    }
    Button(services.sync::requestSync, enabled = !status.syncing, modifier = Modifier.fillMaxWidth().hand(!status.syncing)) {
        Text(stringResource(Res.string.server_sync_now))
    }
    if (services.updates.supported) {
        // Updates from this server (ADR-042, U3): by a tap in «Ещё», or by themselves while the app is away.
        val quiet by services.updates.quiet.collectAsStateWithLifecycle()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(Res.string.update_quiet), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(Res.string.update_quiet_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(quiet, services.updates::setQuiet, modifier = Modifier.hand())
        }
    }

    if (rejected.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.server_rejected_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(Res.string.server_rejected_hint), style = MaterialTheme.typography.bodyMedium)
                rejected.forEach { Text("• ${it.error ?: it.opId}", style = MaterialTheme.typography.bodySmall) }
                TextButton(vm::retryRejected, modifier = Modifier.hand()) { Text(stringResource(Res.string.retry)) }
            }
        }
    }

    if (connection.fingerprint.isNotEmpty()) {
        Text(stringResource(Res.string.server_fingerprint), style = MaterialTheme.typography.titleSmall)
        Fingerprint(connection.fingerprint)
    }

    val canForget = status.pending == 0L && status.rejected == 0L
    OutlinedButton({
        confirmForget = true
    }, enabled = canForget && !vm.busy, modifier = Modifier.fillMaxWidth().hand(canForget && !vm.busy)) {
        Text(stringResource(Res.string.server_forget))
    }
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
                }, modifier = Modifier.hand()) { Text(stringResource(Res.string.server_forget)) }
            },
            dismissButton = {
                TextButton({ confirmForget = false }, modifier = Modifier.hand()) { Text(stringResource(Res.string.server_cancel)) }
            },
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
