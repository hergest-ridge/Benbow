package app.benbow.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benbow.core.content.ROW_MAX
import app.benbow.core.text.Atom
import app.benbow.core.text.BlockStyle
import app.benbow.core.text.ListKind
import app.benbow.core.text.Mark
import app.benbow.core.text.MarkSpan
import app.benbow.core.text.RichText
import app.benbow.core.text.linkAt
import app.benbow.ui.theme.BenbowTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Seamless rich text editor: one text field, formatting drawn from [EditorState.model]. [readOnly] is for viewing old
 * versions and posts. Atom paragraphs (pictures, dividers) are drawn by [atoms] over their own lines.
 */
@Composable
fun BenbowEditor(state: EditorState, modifier: Modifier = Modifier, readOnly: Boolean = false, atoms: AtomViews = AtomViews.Default) {
    BoxWithConstraints(modifier) { EditorBody(state, readOnly, atoms, maxWidth) }
}

@Composable
private fun BoxScope.EditorBody(state: EditorState, readOnly: Boolean, atoms: AtomViews, width: Dp) {
    val colors = MaterialTheme.colorScheme
    val theme = BenbowTheme.colors
    val density = LocalDensity.current
    // A picture link on its way stands as tall as the picture will (ADR-045).
    val coming = LocalPicturesComing.current
    // The note's own font (ADR-047) for its words and headings, at its size; code keeps the monospace.
    val font = LocalNoteFont.current
    val styles = remember(colors, theme, atoms, width, density, coming, font) {
        EditorStyles(colors, theme.dark, theme.tokens, baseSize = 17.sp * (font?.scale ?: 1f), atomHeight = {
            with(density) { atoms.height(it, width, coming).toSp() }
        })
    }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = colors.onSurface,
        fontSize = styles.baseSize,
        lineHeight = TextUnit.Unspecified,
        fontFamily = font?.family ?: MaterialTheme.typography.bodyLarge.fontFamily,
    )
    val measurer = rememberTextMeasurer()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Spoilers (ADR-031): read-only text hides them until a tap opens them all; the editor shows them frosted, and clear
    // where the cursor or selection touches them, so they can still be written.
    val spoilerRanges = remember(state.model) { state.model.spoilerRanges() }
    val laidOut = layout?.takeIf { spoilerRanges.isNotEmpty() && it.matches(state.model) }
    val spoilers = remember(laidOut, spoilerRanges) { laidOut?.spoilerLayout(spoilerRanges) }
    val reveal = remember(state) { SpoilerReveal() }
    val covered: () -> Path? = {
        when {
            spoilers == null -> null
            readOnly -> reveal.hidden(null, spoilers)
            else -> spoilers.path { !state.selection.touches(spoilerRanges[it]) }.takeUnless { it.isEmpty }
        }
    }

    SideEffect { state.styles = styles }
    LaunchedEffect(state) {
        // Moving the cursor without typing drops pending toggles (e.g. bold armed at the old position).
        var lastModel = state.model
        var lastSelection = state.selection
        snapshotFlow { state.model to state.selection }.collect { (model, selection) ->
            if (model === lastModel) {
                state.typingMarks = null
                if (selection != lastSelection) state.selectedAtom = null
            }
            state.cursorPlaced = model === lastModel
            state.noteSelection(selection, lastSelection)
            lastModel = model
            lastSelection = selection
        }
    }
    // Links (ADR-038): a thin underline; reading, a tap opens one (its press shows first); writing, Ctrl+click does.
    val links = rememberLinkActions()
    val linkSpans = remember(state.model) { state.model.linkRanges() }
    // Read by the gesture handlers as they are now: keyed on them, a handler would restart with every key typed.
    val spansNow by rememberUpdatedState(linkSpans)
    var pressedLink by remember { mutableStateOf<MarkSpan?>(null) }
    var hoverLink by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var placedField by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val origin: () -> Offset? = { placedField?.takeIf { it.isAttached }?.positionInRoot() }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipboardActions = remember(state, clipboard, scope) {
        ClipboardActions(
            copy = {
                val text = state.selectedText()
                val entry = text?.let(::plainTextClipEntry)
                if (entry != null) scope.launch { clipboard.setClipEntry(entry) }
                entry != null
            },
            cut = {
                val text = state.selectedText()
                val entry = text?.let(::plainTextClipEntry)
                if (entry != null) {
                    scope.launch { clipboard.setClipEntry(entry) }
                    state.replaceSelection("")
                }
                entry != null
            },
        )
    }

    // Text pasted that the browser's Compose didn't hand to the field (a click left the page's focus on its canvas).
    PasteFallback(focused && !readOnly) { state.replaceSelection(it) }
    EditorClipboardFix(recentlySelected = state::recentlyHadRangeSelection) {
        EditorImeBridge {
            BasicTextField(
                state = state.textFieldState,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { placedField = it }
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { state.handleShortcut(it, clipboardActions) }
                    .then(if (hoverLink) Modifier.pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true) else Modifier)
                    .pointerInput(state, readOnly) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val at = event.changes.firstOrNull()?.position ?: continue
                                hoverLink = event.type != PointerEventType.Exit && (readOnly || event.keyboardModifiers.isCtrlPressed) &&
                                    layout?.takeIf { it.matches(state.model) }?.linkUnder(spansNow, at, 0f) != null
                            }
                        }
                    }
                    .pointerInput(state, styles, readOnly) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val opensLinks =
                                readOnly || currentEvent.keyboardModifiers.isCtrlPressed || currentEvent.keyboardModifiers.isMetaPressed
                            val link = if (opensLinks &&
                                !(readOnly && !reveal.opened && layout?.spoilerAt(state.model, down.position) == true)
                            ) {
                                layout?.takeIf { it.matches(state.model) }?.linkUnder(spansNow, down.position, 6.dp.toPx())
                            } else {
                                null
                            }
                            if (link != null) {
                                down.consume()
                                pressedLink = link
                                val up = awaitLinkTap(down, origin)
                                pressedLink = null
                                if (up != null) {
                                    up.consume()
                                    links.open(link.mark)
                                }
                                return@awaitEachGesture
                            }
                            if (readOnly && !reveal.opened && layout?.spoilerAt(state.model, down.position) == true) {
                                down.consume()
                                val up = waitForUpOrCancellation(PointerEventPass.Initial)
                                if (up != null) {
                                    up.consume()
                                    reveal.open(null, up.position, scope)
                                }
                                return@awaitEachGesture
                            }
                            val index = layout?.let { checkboxAt(it, state.model, styles, down.position) }
                            if (index != null && !readOnly) {
                                down.consume()
                                val up = waitForUpOrCancellation(PointerEventPass.Initial)
                                if (up != null) {
                                    up.consume()
                                    state.toggleChecked(index)
                                }
                                return@awaitEachGesture
                            }
                            // Let the text field place the cursor first (Final pass runs after it), then fix it up.
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    val isTap = change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                                    if (isTap) layout?.let { state.fixCursorAfterTap(it, change.position) }
                                    break
                                }
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                            }
                        }
                    }
                    .drawBehind {
                        val l = layout ?: return@drawBehind
                        drawDecorations(l, state.model, styles, measurer, textStyle)
                        if (l.matches(state.model)) {
                            pressedLink?.let { drawPressedLink(l, it, colors.primary.copy(alpha = 0.14f)) }
                            drawLinkUnderlines(l, linkSpans, colors.primary.copy(alpha = 0.45f))
                        }
                    }
                    .drawWithContent {
                        val path = covered()
                        when {
                            path == null -> drawContent()

                            readOnly -> clipPath(path, ClipOp.Difference) { this@drawWithContent.drawContent() }

                            else -> {
                                clipPath(path, ClipOp.Difference) { this@drawWithContent.drawContent() }
                                drawIntoCanvas { canvas ->
                                    canvas.saveLayer(path.getBounds(), Paint().apply { alpha = FROSTED_TEXT })
                                    clipPath(path) { this@drawWithContent.drawContent() }
                                    canvas.restore()
                                }
                            }
                        }
                    },
                inputTransformation = state.inputTransformation,
                outputTransformation = state.outputTransformation,
                readOnly = readOnly,
                textStyle = textStyle,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    autoCorrectEnabled = true,
                    keyboardType = KeyboardType.Text,
                ),
                cursorBrush = SolidColor(colors.primary),
                lineLimits = TextFieldLineLimits.MultiLine(),
                onTextLayout = { getResult -> layout = getResult() },
            )
        }
    }
    if (spoilers != null && !(readOnly && reveal.done)) {
        // The editor's frosted dots stand still: calm while writing, and nothing to redraw.
        val time = spoilerClock(readOnly)
        Spacer(
            Modifier.matchParentSize().spoilerDots(rememberSpoilerDots(), colors.onSurface, time, {
                if (readOnly) reveal.dotsAlpha() else FROSTED_DOTS
            }, covered),
        )
    }
    // Each atom over its own line, which the paragraph style made exactly as tall. It is keyed by its paragraph, so
    // typing elsewhere never rebuilds a picture (which would load and blink); for the frame the layout trails an edit,
    // an atom stays where it last was.
    val current = layout
    val placed = remember { HashMap<String, Pair<Float, Float>>() }
    val host = remember(state, readOnly) { AtomHost(state, readOnly) }
    // Where an attachment carried from the strip would go (owner, 2026-10-30): the paragraph boundary nearest the pointer;
    // anywhere below the words, after them (a short note's page is mostly below them); above, only just above them.
    state.dropLocator = if (readOnly || current == null) {
        null
    } else {
        { window ->
            val field = placedField?.takeIf { it.isAttached }
            val local = field?.windowToLocal(window)
            if (field == null || local == null || local.x !in -SLACK..field.size.width + SLACK || local.y < -SLACK) {
                null
            } else {
                val model = state.model
                (0..model.paragraphs.size).minByOrNull { abs(boundaryY(current, model, it) - local.y) }
            }
        }
    }
    // Beside a picture (or a row with room) in the text: the carried attachment joins it in a row (ADR-061), over the
    // picture's outer third on either side.
    state.rowLocator = if (readOnly || current == null) {
        null
    } else {
        { window ->
            val field = placedField?.takeIf { it.isAttached }
            val local = field?.windowToLocal(window)
            val w = field?.size?.width?.toFloat() ?: 0f
            if (field == null || local == null || w <= 0f || local.x !in 0f..w) {
                null
            } else {
                state.model.paragraphs.firstOrNull { p ->
                    val spot = placed[p.key]
                    val room = when (val atom = p.atom) {
                        is Atom.Attachment -> atoms.selectable(atom)
                        is Atom.AttachmentRow -> atom.refs.size < ROW_MAX
                        else -> false
                    }
                    room && spot != null && local.y in spot.first..spot.second
                }?.let { p ->
                    when {
                        local.x < w * ROW_SIDE -> EditorState.RowDrop(p.key, before = true)
                        local.x > w * (1 - ROW_SIDE) -> EditorState.RowDrop(p.key, before = false)
                        else -> null
                    }
                }
            }
        }
    }
    if (current != null) {
        val model = state.model
        val fresh = current.matches(model)
        state.rowDropAt?.let { drop -> placed[drop.key]?.let { drop to it } }?.let { (drop, spot) ->
            // Its place in the row: a bar down the picture's side.
            Box(
                Modifier.offset {
                    IntOffset(if (drop.before) 0 else (width - 4.dp).roundToPx(), spot.first.roundToInt())
                }.width(4.dp).height(
                    with(density) {
                        (spot.second - spot.first).toDp()
                    },
                ).background(colors.primary, RoundedCornerShape(2.dp)),
            )
        }
        state.dropAt?.takeIf { it <= model.paragraphs.size }?.let { at ->
            // Its place: a line between the paragraphs, as thick as a finger can see.
            val y = boundaryY(current, model, at)
            Box(
                Modifier.offset { IntOffset(0, (y - 2.dp.toPx()).roundToInt()) }.width(width).height(4.dp)
                    .background(colors.primary, RoundedCornerShape(2.dp)),
            )
        }
        val selectedOutline = colors.primary
        CompositionLocalProvider(LocalAtomHost provides host) {
            model.paragraphs.forEachIndexed { i, paragraph ->
                val atom = paragraph.atom ?: return@forEachIndexed
                val spot = placed[paragraph.key]?.takeIf { !fresh } ?: run {
                    val line = current.getLineForOffset(model.paragraphStarts[i].coerceAtMost(current.layoutInput.text.length))
                    (current.getLineTop(line) to current.getLineBottom(line)).also { if (fresh) placed[paragraph.key] = it }
                }
                // The first Backspace selects a picture or a divider (ADR-038); a card draws its own outline.
                val outlined = !readOnly && state.selectedAtom == paragraph.key && atom !is Atom.LinkCard && atom !is Atom.AttachmentRow
                key(paragraph.key) {
                    Box(
                        Modifier.offset {
                            IntOffset(0, spot.first.roundToInt())
                        }.width(width).height(with(density) { (spot.second - spot.first).toDp() }).testTag("atom:${paragraph.key}")
                            .then(if (outlined) Modifier.border(2.dp, selectedOutline, RoundedCornerShape(12.dp)) else Modifier),
                    ) { atoms.Content(atom, paragraph.key) }
                }
            }
        }
        if (!readOnly && fresh && state.linkRequest == null) {
            val widthPx = with(density) { width.toPx() }
            val card = state.selectedAtom?.let { state.card(it) }
            // A picture selected while writing (owner, 2026-10-30): open it, how it shows, out of the text.
            val picture = state.selectedAtom?.let { key ->
                model.paragraphs.firstOrNull { it.key == key }?.let { p ->
                    (p.atom as? Atom.Attachment)?.takeIf(atoms::selectable)?.let {
                        key to
                            it
                    }
                }
            }
            // A picture chosen in a row (ADR-061): the row's bubble acts on it.
            val row = state.selectedAtom?.let { key ->
                model.paragraphs.firstOrNull { it.key == key }?.let { p -> (p.atom as? Atom.AttachmentRow)?.let { key to it } }
            }
            val sel = state.selection
            val span = if (card == null && picture == null && row == null && focused && sel.collapsed &&
                state.cursorPlaced
            ) {
                model.linkAt(sel.min)
            } else {
                null
            }
            when {
                picture != null -> {
                    val (key, atom) = picture
                    val spot = placed[key]
                    if (spot != null) {
                        BubbleAt(Rect(widthPx - 1f, spot.first, widthPx, spot.second), Modifier.matchParentSize()) {
                            PictureBubble(
                                onOpen = { atoms.openPicture(atom) },
                                onSettings = {
                                    state.selectedAtom = null
                                    atoms.pictureSettings(atom)
                                },
                                onRemove = { state.removeAtom(key) },
                                onRow = if (state.canJoinAbove(key, atoms::selectable)) {
                                    { state.joinAbove(key, atoms::selectable) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }

                row != null -> {
                    val (key, atom) = row
                    val part = state.selectedPart?.takeIf { it in atom.refs } ?: atom.refs.first()
                    val spot = placed[key]
                    if (spot != null) {
                        BubbleAt(Rect(widthPx - 1f, spot.first, widthPx, spot.second), Modifier.matchParentSize()) {
                            RowBubble(
                                onOpen = { atoms.openPicture(Atom.Attachment(part)) },
                                onSettings = {
                                    state.selectedAtom = null
                                    atoms.pictureSettings(Atom.Attachment(part))
                                },
                                onOut = { state.takeOutOfRow(key, part) },
                                onSplit = { state.splitRow(key) },
                                onRemove = { state.removeAtom(key) },
                            )
                        }
                    }
                }

                card != null -> {
                    val (index, atom) = card
                    val key = model.paragraphs[index].key
                    val spot = placed[key]
                    if (spot != null) {
                        BubbleAt(Rect(widthPx - 1f, spot.first, widthPx, spot.second), Modifier.matchParentSize()) {
                            CardBubble(
                                atom,
                                onOpen = { links.open(Mark.Link(atom.href)) },
                                onLarge = { state.setCardLarge(key, it) },
                                onToText = { state.cardToText(key) },
                                onRefresh = { state.refreshCard(key) },
                                onEdit = { state.requestCardEdit(key) },
                                onRemove = { state.removeAtom(key) },
                            )
                        }
                    }
                }

                span != null -> current.rangeRects(span.start, span.end).firstOrNull()?.let { rect ->
                    BubbleAt(rect, Modifier.matchParentSize()) {
                        LinkBubble(
                            span.mark,
                            onOpen = { links.open(span.mark) },
                            onEdit = state::requestLink,
                            onCopy = (span.mark as? Mark.Link)?.let { link -> { links.copy(link.href) } },
                            onUnlink = { state.unlinkAt(sel.min) },
                        )
                    }
                }
            }
        }
    }
}

private fun TextLayoutResult.spoilerAt(model: RichText, position: Offset): Boolean {
    if (!matches(model)) return false
    val ranges = model.spoilerRanges()
    return ranges.isNotEmpty() && spoilerLayout(ranges).hit(position)
}

/** How much of frosted spoiler text and dots shows in the editor. */
private const val FROSTED_TEXT = 0.35f
private const val FROSTED_DOTS = 0.55f

/** Copy/cut of model text; each returns false when it didn't handle the action (platform default runs). */
private class ClipboardActions(val copy: () -> Boolean, val cut: () -> Boolean)

/** The displayed text may carry one extra [TAIL_GLYPH]; anything else means the layout is stale. */
private fun TextLayoutResult.matches(model: RichText): Boolean =
    layoutInput.text.length.let { it == model.text.length || it == model.text.length + 1 }

/**
 * A tap right of a line's end lands after the paragraph-break glyph, i.e. at the start of the next
 * paragraph. If the tap was on the glyph's own line, move the cursor back before the glyph.
 */
private fun EditorState.fixCursorAfterTap(layout: TextLayoutResult, position: Offset) {
    val m = model
    if (!layout.matches(m)) return
    val offset = layout.getOffsetForPosition(position)
    val index = m.paragraphIndexAt(offset)
    if (index == 0 || offset != m.paragraphStarts[index] || selection != TextRange(offset)) return
    val glyphLine = layout.getLineForOffset(offset - 1)
    if (position.y in layout.getLineTop(glyphLine)..layout.getLineBottom(glyphLine)) {
        textFieldState.edit { selection = TextRange(offset - 1) }
    }
}

private fun EditorState.handleShortcut(event: KeyEvent, clipboard: ClipboardActions): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.Tab && currentParagraph.list != null) {
        indent(if (event.isShiftPressed) -1 else 1)
        return true
    }
    if (!event.isCtrlPressed && !event.isMetaPressed) return false
    when (event.key) {
        Key.Z -> if (event.isShiftPressed) redo() else undo()
        Key.Y -> redo()
        Key.B -> toggleMark(Mark.Bold)
        Key.I -> toggleMark(Mark.Italic)
        Key.U -> toggleMark(Mark.Underline)
        Key.E -> toggleMark(Mark.Code)
        Key.K -> requestLink()
        Key.C -> return clipboard.copy()
        Key.X -> return clipboard.cut()
        else -> return false
    }
    return true
}

private fun Density.checkboxAt(layout: TextLayoutResult, model: RichText, styles: EditorStyles, position: Offset): Int? {
    if (!layout.matches(model)) return null
    val index = model.paragraphIndexAt(layout.getOffsetForPosition(position))
    val paragraph = model.paragraphs[index]
    if (paragraph.list != ListKind.Check) return null
    val line = layout.getLineForOffset(model.paragraphStarts[index])
    if (position.y < layout.getLineTop(line) || position.y > layout.getLineBottom(line)) return null
    val right = styles.indent(paragraph).toPx()
    return if (position.x in (right - 36.dp.toPx())..right) index else null
}

private fun DrawScope.drawDecorations(
    layout: TextLayoutResult,
    model: RichText,
    styles: EditorStyles,
    measurer: TextMeasurer,
    textStyle: TextStyle,
) {
    if (!layout.matches(model)) return
    val paragraphs = model.paragraphs
    val starts = model.paragraphStarts
    fun firstLine(i: Int) = layout.getLineForOffset(starts[i])
    fun top(i: Int) = layout.getLineTop(firstLine(i))
    fun bottom(i: Int) = layout.getLineBottom(layout.getLineForOffset(model.paragraphEnd(i)))
    val colors = styles.colors
    val pad = 4.dp.toPx()

    forEachGroup(paragraphs.size, { paragraphs[it].callout?.key }) { from, to ->
        val callout = paragraphs[from].callout!!
        drawRoundRect(
            color = styles.calloutBackground(callout.tone),
            topLeft = Offset(0f, top(from) - pad),
            size = Size(size.width, bottom(to) - top(from) + 2 * pad),
            cornerRadius = CornerRadius(12.dp.toPx()),
        )
        val icon = measurer.measure(callout.icon, textStyle)
        val lineHeight = layout.getLineBottom(firstLine(from)) - top(from)
        drawText(icon, topLeft = Offset(10.dp.toPx(), top(from) + (lineHeight - icon.size.height) / 2))
    }
    forEachGroup(paragraphs.size, { if (paragraphs[it].style == BlockStyle.Code) "code" else null }) { from, to ->
        val left = styles.calloutIndent(paragraphs[from]).sp.toPx()
        drawRoundRect(
            color = colors.surfaceVariant,
            topLeft = Offset(left, top(from) - pad),
            size = Size(size.width - left, bottom(to) - top(from) + 2 * pad),
            cornerRadius = CornerRadius(8.dp.toPx()),
        )
    }
    forEachGroup(paragraphs.size, { if (paragraphs[it].style == BlockStyle.Quote) "quote" else null }) { from, to ->
        val x = styles.calloutIndent(paragraphs[from]).sp.toPx() + 4.dp.toPx()
        drawRoundRect(
            color = colors.primary.copy(alpha = 0.6f),
            topLeft = Offset(x, top(from)),
            size = Size(3.dp.toPx(), bottom(to) - top(from)),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }

    val numbers = listNumbers(model)
    paragraphs.forEachIndexed { i, p ->
        val kind = p.list ?: return@forEachIndexed
        val line = firstLine(i)
        val centerY = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
        val right = styles.indent(p).toPx() - 8.dp.toPx()
        when (kind) {
            ListKind.Bullet -> {
                val radius = 3.dp.toPx()
                val center = Offset(right - 5.dp.toPx(), centerY)
                if (p.level % 2 == 1) {
                    drawCircle(colors.onSurfaceVariant, radius, center)
                } else {
                    drawCircle(colors.onSurfaceVariant, radius, center, style = Stroke(1.5.dp.toPx()))
                }
            }

            ListKind.Number -> {
                val label = measurer.measure("${numbers[i]}.", textStyle.copy(color = colors.onSurfaceVariant))
                drawText(label, topLeft = Offset(right - label.size.width, centerY - label.size.height / 2))
            }

            ListKind.Check -> drawCheckbox(
                Offset(right - 18.dp.toPx(), centerY - 9.dp.toPx()),
                p.checked,
                colors.primary,
                colors.onPrimary,
                colors.onSurfaceVariant,
            )
        }
    }
}

private fun DrawScope.drawCheckbox(topLeft: Offset, checked: Boolean, fill: Color, mark: Color, outline: Color) {
    val side = 18.dp.toPx()
    val corner = CornerRadius(4.dp.toPx())
    if (!checked) {
        drawRoundRect(outline, topLeft, Size(side, side), corner, style = Stroke(1.6.dp.toPx()))
        return
    }
    drawRoundRect(fill, topLeft, Size(side, side), corner)
    val path = Path().apply {
        moveTo(topLeft.x + side * 0.25f, topLeft.y + side * 0.52f)
        lineTo(topLeft.x + side * 0.43f, topLeft.y + side * 0.70f)
        lineTo(topLeft.x + side * 0.76f, topLeft.y + side * 0.32f)
    }
    drawPath(path, mark, style = Stroke(2.dp.toPx()))
}

/** Calls [block] for each run of consecutive indices sharing a non-null group key. */
private inline fun forEachGroup(count: Int, key: (Int) -> Any?, block: (from: Int, to: Int) -> Unit) {
    var i = 0
    while (i < count) {
        val k = key(i)
        if (k == null) {
            i++
            continue
        }
        var j = i
        while (j + 1 < count && key(j + 1) == k) j++
        block(i, j)
        i = j + 1
    }
}

private fun listNumbers(model: RichText): IntArray {
    val result = IntArray(model.paragraphs.size)
    val counters = IntArray(RichText.MAX_LEVEL + 2)
    model.paragraphs.forEachIndexed { i, p ->
        when (p.list) {
            null -> counters.fill(0)

            ListKind.Number -> {
                counters[p.level]++
                counters.fill(0, p.level + 1)
                result[i] = counters[p.level]
            }

            else -> counters.fill(0, p.level)
        }
    }
    return result
}

/** Where paragraph [index] starts down the text ([model]'s count: its end): a carried attachment's place. */
private fun boundaryY(layout: TextLayoutResult, model: RichText, index: Int): Float = if (index >= model.paragraphs.size) {
    layout.size.height.toFloat()
} else {
    layout.getLineTop(layout.getLineForOffset(model.paragraphStarts[index].coerceAtMost(layout.layoutInput.text.length)))
}

/** How far past the text a carried attachment still finds a place in it. */
private const val SLACK = 48f

/** How much of a picture's width, at either side, takes a carried attachment into a row with it. */
private const val ROW_SIDE = 0.3f
