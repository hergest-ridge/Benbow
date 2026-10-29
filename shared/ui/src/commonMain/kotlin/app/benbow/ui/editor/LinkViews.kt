package app.benbow.ui.editor

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.benbow.core.text.Atom
import app.benbow.core.text.Links
import app.benbow.core.text.Mark
import app.benbow.core.text.MarkSpan
import app.benbow.core.text.RichText
import app.benbow.ui.resources.Res
import app.benbow.ui.resources.link_copy
import app.benbow.ui.resources.link_open
import app.benbow.ui.theme.BenbowPalette
import app.benbow.ui.theme.BenbowTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** What the app does with links (ADR-038): opens them (a browser sheet, an entry) and copies their addresses. */
interface LinkHandler {
    fun open(mark: Mark)

    fun copy(href: String)
}

val LocalLinkHandler = staticCompositionLocalOf<LinkHandler?> { null }

/** Draws a card's picture (a blob's SHA-256); [large] is the big look. Null: cards show the site's letter. */
val LocalLinkPicture = staticCompositionLocalOf<(@Composable (sha256: String, large: Boolean, modifier: Modifier) -> Unit)?> { null }

/** Addresses whose pages are being read right now: their cards shimmer where the title will be. */
val LocalLinkLoading = compositionLocalOf<Set<String>> { emptySet() }

/** A picture on its way to an attachment (ADR-045): its size once its first bytes came, and whether it is a GIF. */
data class PictureLoading(val width: Int? = null, val height: Int? = null, val gif: Boolean = false)

/** Addresses surely being pictures on their way: their cards show the picture's place instead of a row. */
val LocalPicturesComing = compositionLocalOf<Map<String, PictureLoading>> { emptyMap() }

/** The app's [LinkHandler], or the platform's own browser and clipboard where there is none (previews, tests). */
@Composable
fun rememberLinkActions(): LinkHandler {
    val handler = LocalLinkHandler.current
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(handler, uri, clipboard, scope) {
        handler ?: object : LinkHandler {
            override fun open(mark: Mark) {
                if (mark is Mark.Link && Links.isOpenable(mark.href)) runCatching { uri.openUri(mark.href) }
            }

            override fun copy(href: String) {
                plainTextClipEntry(href)?.let { scope.launch { clipboard.setClipEntry(it) } }
            }
        }
    }
}

// ---- links in text ----

/** Link runs of [text] within `[from, to)`, as offsets relative to [from]; spoilered words are left out. */
fun RichText.linkRanges(from: Int = 0, to: Int = text.length): List<MarkSpan> {
    val spoilers = marks.filter { it.mark == Mark.Spoiler }
    return marks.filter { it.mark.group == "link" && it.end > from && it.start < to }
        .filter { link -> spoilers.none { it.start < link.end && it.end > link.start } }
        .map { MarkSpan(it.mark, maxOf(it.start, from) - from, minOf(it.end, to) - from) }
}

/** One rectangle per line `[start, end)` covers: the line's top to bottom, the glyphs' left to right. */
fun TextLayoutResult.rangeRects(start: Int, end: Int): List<Rect> {
    val length = layoutInput.text.length
    val s = start.coerceIn(0, length)
    val e = end.coerceIn(0, length)
    if (e <= s) return emptyList()
    return (getLineForOffset(s)..getLineForOffset(e - 1)).mapNotNull { line ->
        val lineStart = getLineStart(line)
        val lineEnd = getLineEnd(line, visibleEnd = true)
        val ls = maxOf(s, lineStart)
        val le = minOf(e, lineEnd)
        if (le <= ls) return@mapNotNull null
        val x1 = if (ls == lineStart) getLineLeft(line) else getHorizontalPosition(ls, usePrimaryDirection = true)
        val x2 = if (le >= lineEnd) getLineRight(line) else getHorizontalPosition(le, usePrimaryDirection = true)
        Rect(minOf(x1, x2), getLineTop(line), maxOf(x1, x2), getLineBottom(line)).takeIf { it.width > 0f }
    }
}

/** The link under [position] ([links] as [linkRanges] gives them), with a little room around its words for a finger. */
fun TextLayoutResult.linkUnder(links: List<MarkSpan>, position: Offset, slop: Float): MarkSpan? = links.firstOrNull { link ->
    rangeRects(link.start, link.end).any { r ->
        Rect(r.left - slop, r.top - slop / 2, r.right + slop, r.bottom + slop / 2).contains(position)
    }
}

/**
 * Waits for the finger that went [down] on a link to lift as a tap: before a long press, without sliding off the words
 * and without the page scrolling under it ([origin]: where the text is on screen now). Null when it became anything else.
 */
suspend fun AwaitPointerEventScope.awaitLinkTap(down: PointerInputChange, origin: () -> Offset?): PointerInputChange? {
    val start = origin()
    val slop = viewConfiguration.touchSlop
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return null
        val now = origin()
        val scrolled = start != null && now != null && (now - start).getDistance() > slop
        if (scrolled || (change.position - down.position).getDistance() > slop) return null
        if (change.changedToUpIgnoreConsumed()) {
            return change.takeIf { it.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis }
        }
    }
}

/** A thin line a little under each link's baseline (the font's own underline is heavy and sits on the letters). */
fun DrawScope.drawLinkUnderlines(layout: TextLayoutResult, links: List<MarkSpan>, color: Color) {
    val thickness = 1.dp.toPx()
    val gap = 2.5.dp.toPx()
    for (link in links) {
        for (rect in layout.rangeRects(link.start, link.end)) {
            val line = layout.getLineForVerticalPosition(rect.center.y)
            val y = (layout.getLineBaseline(line) + gap).coerceAtMost(rect.bottom - thickness)
            drawLine(color, Offset(rect.left, y), Offset(rect.right, y), thickness)
        }
    }
}

/** The pressed link's soft background, drawn while the finger is on it (before it lifts, it can still slide off). */
fun DrawScope.drawPressedLink(layout: TextLayoutResult, link: MarkSpan, color: Color) {
    val pad = 2.dp.toPx()
    for (r in layout.rangeRects(link.start, link.end)) {
        drawRoundRect(
            color,
            Offset(r.left - pad, r.top),
            androidx.compose.ui.geometry.Size(r.width + 2 * pad, r.height),
            CornerRadius(4.dp.toPx()),
        )
    }
}

// ---- cards ----

/** How tall card [card] stands across [width], with a little air above and below. */
fun linkCardHeight(card: Atom.LinkCard, width: Dp, coming: PictureLoading? = null): Dp = when {
    coming != null -> comingHeight(coming, width) + CARD_AIR
    card.large && card.image != null -> largePictureHeight(width) + LARGE_TEXT + CARD_AIR
    else -> ROW_HEIGHT + CARD_AIR
}

/**
 * A coming picture's place, as tall as the picture will stand in the text (the app's inline pictures: its own
 * proportions once known, between 120 and 520 dp), so nothing jumps when it lands.
 */
private fun comingHeight(coming: PictureLoading, width: Dp): Dp {
    val w = coming.width ?: 0
    val h = coming.height ?: 0
    return (if (w > 0 && h > 0) width * (h.toFloat() / w) else width * 0.66f).coerceIn(120.dp, 520.dp)
}

private fun largePictureHeight(width: Dp): Dp = (width / 1.91f).coerceIn(120.dp, 280.dp)

private val ROW_HEIGHT = 72.dp
private val LARGE_TEXT = 108.dp
private val CARD_AIR = 8.dp

/**
 * A link as a card (ADR-038): a row with the site, the title and a small picture, or the big look with the picture across.
 * The press shows before the finger lifts (it can slide off to change its mind); [onLongClick] is the reader's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LinkCardView(
    card: Atom.LinkCard,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    // Surely a picture on its way: the picture's place, not a row (ADR-045).
    LocalPicturesComing.current[card.href]?.let { return PictureComingView(card, it, modifier, selected, onClick) }
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(if (pressed) 90 else 160))
    val shape = RoundedCornerShape(12.dp)
    val loading = card.fetched == null && card.title == null && card.href in LocalLinkLoading.current
    Surface(
        shape = shape,
        color = colors.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else BenbowTheme.colors.hairline),
        modifier = modifier.scale(scale).clip(shape).pointerHoverIcon(PointerIcon.Hand)
            .combinedClickable(
                interaction,
                androidx.compose.material3.ripple(),
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
    ) {
        val picture = LocalLinkPicture.current
        if (card.large && card.image != null && picture != null) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f).background(colors.surfaceContainerHigh)) {
                    picture(card.image!!, true, Modifier.fillMaxSize())
                }
                Column(Modifier.fillMaxWidth().height(LARGE_TEXT).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    CardWords(card, loading, titleLines = 2)
                    card.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(colors.primary.copy(alpha = 0.8f)))
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), verticalArrangement = Arrangement.Center) {
                    CardWords(card, loading, titleLines = 2)
                }
                Thumb(card, picture, Modifier.padding(end = 10.dp).size(48.dp))
            }
        }
    }
}

@Composable
private fun CardWords(card: Atom.LinkCard, loading: Boolean, titleLines: Int) {
    val colors = MaterialTheme.colorScheme
    Text(
        card.site?.takeIf { it.isNotBlank() } ?: Links.host(card.href).ifEmpty { Links.display(card.href) },
        style = MaterialTheme.typography.labelMedium,
        color = colors.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (loading) {
        val pulse by rememberInfiniteTransition().animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(700), RepeatMode.Reverse))
        Spacer(
            Modifier.padding(top = 4.dp).fillMaxWidth(0.7f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(
                colors.onSurface.copy(
                    alpha =
                    pulse * 0.25f,
                ),
            ),
        )
        return
    }
    val title = card.title?.takeIf { it.isNotBlank() }
    Text(
        title ?: Links.display(card.href),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (title != null) FontWeight.Medium else null,
        color = if (title != null) colors.onSurface else colors.onSurfaceVariant,
        maxLines = titleLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The page's picture, or the site's first letter on a tint of its own. */
@Composable
private fun Thumb(card: Atom.LinkCard, picture: (@Composable (String, Boolean, Modifier) -> Unit)?, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val image = card.image
    if (image != null && picture != null) {
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            picture(image, false, Modifier.fillMaxSize())
        }
        return
    }
    val host = Links.host(card.href)
    val theme = BenbowTheme.colors
    val token = BenbowPalette.tokens[(host.hashCode() and Int.MAX_VALUE) % BenbowPalette.tokens.size]
    Box(
        modifier.clip(shape).background(theme.tokens.tint(token, theme.dark) ?: MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            host.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "·",
            style = MaterialTheme.typography.titleMedium,
            color = theme.tokens.text(token, theme.dark) ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A card where it is read (the feed, a post): a tap opens it, a long press offers to open or copy its address. */
@Composable
fun ReadingLinkCard(card: Atom.LinkCard, modifier: Modifier = Modifier) {
    val links = rememberLinkActions()
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        LinkCardView(card, Modifier.fillMaxSize(), onClick = { links.open(Mark.Link(card.href)) }, onLongClick = { menu = true })
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.link_open)) }, onClick = {
                menu = false
                links.open(Mark.Link(card.href))
            }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand))
            DropdownMenuItem(text = { Text(stringResource(Res.string.link_copy)) }, onClick = {
                menu = false
                links.copy(card.href)
            }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand))
        }
    }
}
