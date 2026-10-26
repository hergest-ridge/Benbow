package app.benbow.app

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.node.DelegatableNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

/**
 * «Подсветка вложений при наведении» (Ещё → Оформление, the web's own, off by default; owner): pictures, videos and
 * galleries don't light up under the pointer, in the feed or in a post. Buttons and links keep theirs, and a press on
 * a picture still shows. Elsewhere (desktop, phones) they light up as before.
 */
internal val LocalMediaHover = compositionLocalOf { true }

/** The indication for a picture, video or gallery that opens on a click: the theme's, without its hover unless asked. */
@Composable
internal fun mediaIndication(): Indication? {
    val indication = LocalIndication.current
    val hover = LocalMediaHover.current
    return remember(indication, hover) { if (hover) indication else (indication as? IndicationNodeFactory)?.let(::WithoutHover) }
}

private class WithoutHover(private val inner: IndicationNodeFactory) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = inner.create(HoverFree(interactionSource))

    override fun equals(other: Any?) = other is WithoutHover && other.inner == inner

    override fun hashCode() = inner.hashCode()
}

/** What [source] tells, the pointer's coming and going left out. */
private class HoverFree(source: InteractionSource) : InteractionSource {
    override val interactions: Flow<Interaction> = source.interactions.filter { it !is HoverInteraction }
}
