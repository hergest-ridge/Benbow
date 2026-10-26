@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import app.benbow.client.toByteArray
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.js.Promise

/**
 * Color emoji from our own server (ADR-029). Compose for Web draws text with the fonts it has and fetches whatever is
 * missing from fonts.gstatic.com; the journal's pages must not reach anywhere but the Benbow server (the server's
 * Content-Security-Policy blocks it too). The ten slices of Noto Color Emoji load before the first frame, so no text is
 * drawn with empty boxes first, and so do two of Noto Sans Symbols 2: the symbols the UI font lacks (★ in a link card's
 * title, arrows, shapes), which desktop and Android take from the system.
 */
@Composable
fun WithBundledFonts(onReady: () -> Unit, content: @Composable () -> Unit) {
    val resolver = LocalFontFamilyResolver.current
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(resolver) {
        val slices = coroutineScope {
            (0..9).map { i ->
                async {
                    runCatching { fetchBytes("fonts/noto-color-emoji-$i.ttf").await<JsAny>().toByteArray() }
                        .onFailure { println("Benbow: emoji slice $i didn't load: ${it.message}") }
                        .getOrNull()
                        ?.let { i to it }
                }
            }.awaitAll().filterNotNull()
        }
        for ((i, bytes) in slices) resolver.preload(FontFamily(Font("Noto Color Emoji $i", bytes)))
        val symbols = coroutineScope {
            SYMBOL_SLICES.map { slice ->
                async {
                    runCatching { fetchBytes("fonts/noto-sans-symbols-2-$slice.ttf").await<JsAny>().toByteArray() }
                        .onFailure { println("Benbow: symbols $slice didn't load: ${it.message}") }
                        .getOrNull()
                        ?.let { slice to it }
                }
            }.awaitAll().filterNotNull()
        }
        for ((slice, bytes) in symbols) resolver.preload(FontFamily(Font("Noto Sans Symbols 2 $slice", bytes)))
        ready = true
        onReady()
    }
    if (ready) content()
}

private val SYMBOL_SLICES = listOf("symbols", "math")

private fun fetchBytes(url: String): Promise<JsAny> = js(
    "fetch(url).then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.arrayBuffer(); }).then(b => new Uint8Array(b))",
)
