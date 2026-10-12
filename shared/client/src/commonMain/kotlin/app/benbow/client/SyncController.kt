package app.benbow.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the sync indicator shows. [pending] counts ops waiting for the server, [rejected] ops that need
 * the user; [completed] counts finished attempts (lets a pull-to-refresh know when its sync is over).
 */
data class SyncStatus(
    val syncing: Boolean = false,
    val completed: Int = 0,
    val last: SyncResult? = null,
    val lastSuccessAt: Long? = null,
    val pending: Long = 0,
    val rejected: Long = 0,
)

/**
 * Decides when to sync (docs/sync.md#sync-triggers): on request (start, foreground, pull-to-refresh,
 * after pairing), [LOCAL_CHANGE_DELAY] after local changes, every [PERIODIC] while in the foreground,
 * and with exponential backoff after failures. Syncs never overlap.
 */
class SyncController(
    private val scope: CoroutineScope,
    private val sync: suspend () -> SyncResult,
    private val pending: Flow<Long>,
    rejected: Flow<Long>,
    private val now: () -> Long,
    private val random: Random = Random.Default,
) {
    private val state = MutableStateFlow(SyncStatus())
    private val requests = Channel<Unit>(Channel.CONFLATED)

    val status: StateFlow<SyncStatus> = combine(state, pending, rejected) { s, p, r -> s.copy(pending = p, rejected = r) }
        .stateIn(scope, SharingStarted.Eagerly, SyncStatus())

    @Volatile
    var foreground: Boolean = false
        set(value) {
            field = value
            if (value) requestSync()
        }

    fun requestSync() {
        requests.trySend(Unit)
    }

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch { pending.filter { it > 0 }.debounce(LOCAL_CHANGE_DELAY).collect { requestSync() } }
        scope.launch {
            var failures = 0
            while (isActive) {
                val wait = when {
                    failures > 0 -> backoff(failures)
                    foreground -> PERIODIC
                    else -> Duration.INFINITE
                }
                withTimeoutOrNull(wait) { requests.receive() }
                state.update { it.copy(syncing = true) }
                val result = sync()
                failures = if (result is SyncResult.Failed) failures + 1 else 0
                state.update {
                    it.copy(
                        syncing = false,
                        completed = it.completed + 1,
                        last = result,
                        lastSuccessAt = if (result is SyncResult.Done) now() else it.lastSuccessAt,
                    )
                }
            }
        }
    }

    private fun backoff(failures: Int): Duration {
        val base = (FIRST_RETRY * (1 shl (failures - 1).coerceAtMost(10))).coerceAtMost(MAX_RETRY)
        return base * (0.8 + 0.4 * random.nextDouble())
    }

    companion object {
        val LOCAL_CHANGE_DELAY = 2.seconds
        val PERIODIC = 2.minutes
        val FIRST_RETRY = 5.seconds
        val MAX_RETRY = 5.minutes
    }
}
