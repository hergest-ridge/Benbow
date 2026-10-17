package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import app.benbow.core.speech.fitPhrase
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** A dictation target on a screen: its edits run on the screen's own thread, and it knows when the screen is gone. */
internal abstract class ScreenTarget(private val ui: CoroutineContext) : DictationTarget {
    @Volatile
    var gone = false

    /** Puts [phrase] in, on the screen's thread; false when there's nowhere to put it. */
    protected abstract fun put(phrase: String): Boolean

    protected suspend fun <T> onScreen(block: () -> T): T = withContext(ui) { block() }

    override suspend fun insert(phrase: String): Boolean = onScreen { !gone && put(phrase) }
}

/** The thread a composable's state is changed on. */
@Composable
internal fun rememberScreenThread(): CoroutineContext {
    val scope = rememberCoroutineScope()
    return remember(scope) { scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext }
}

/**
 * Dictation into a plain text field (7.10d: quick notes): each phrase goes after the text, fitted to it. A quick note
 * has no entry yet, so a take that has to stay a voice note goes to a new note of its own.
 */
@Composable
internal fun rememberFieldDictation(text: () -> String, onText: (String) -> Unit): DictationTarget {
    val ui = rememberScreenThread()
    val current by rememberUpdatedState(text)
    val change by rememberUpdatedState(onText)
    val target = remember(ui) {
        object : ScreenTarget(ui) {
            override fun put(phrase: String): Boolean {
                val now = current()
                change(now + fitPhrase(now, phrase))
                return true
            }

            override suspend fun begin(): String? = null
        }
    }
    DisposableEffect(target) { onDispose { target.gone = true } }
    return target
}
