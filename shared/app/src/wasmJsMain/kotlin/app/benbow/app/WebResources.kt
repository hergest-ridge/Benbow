package app.benbow.app

import app.benbow.app.resources.Res
import app.benbow.app.resources.allPluralStringResources
import app.benbow.app.resources.allStringArrayResources
import app.benbow.app.resources.allStringResources
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray

/**
 * In the browser, texts load asynchronously and a screen's first frame would get empty ones (and empty lists for
 * month names). Reading them all once before the app shows fills the cache, so every frame has its words.
 */
suspend fun preloadTexts() = coroutineScope {
    Res.allStringResources.values.forEach { launch { getString(it) } }
    Res.allStringArrayResources.values.forEach { launch { getStringArray(it) } }
    Res.allPluralStringResources.values.forEach { launch { getPluralString(it, 1) } }
}
