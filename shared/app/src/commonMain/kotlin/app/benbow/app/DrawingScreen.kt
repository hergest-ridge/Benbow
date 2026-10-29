package app.benbow.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.drawing_canvas
import app.benbow.app.resources.drawing_eraser
import app.benbow.app.resources.drawing_marker
import app.benbow.app.resources.drawing_more_room
import app.benbow.app.resources.drawing_pen
import app.benbow.app.resources.drawing_redo
import app.benbow.app.resources.drawing_title
import app.benbow.app.resources.drawing_undo
import app.benbow.client.byteSourceOf
import app.benbow.client.readAllText
import app.benbow.core.ink.InkDrawing
import app.benbow.core.ink.InkGeometry
import app.benbow.core.ink.InkStroke
import app.benbow.core.ink.InkStrokeBuilder
import app.benbow.core.ink.InkSvg
import app.benbow.core.ink.InkTool
import app.benbow.ui.sideScroll
import app.benbow.ui.theme.BenbowPalette
import app.benbow.ui.theme.BenbowTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** A drawing saved here (7.5): the editor puts it in the text, or swaps the old one ([attachmentId] null = erased). */
data class Drawn(val entryId: String, val previous: String?, val attachmentId: String?)

/** Saves a drawing as a new attachment (an SVG); the old one, if any, is let go by the editor once the text points here. */
internal suspend fun AppServices.saveDrawing(entryId: String, previous: String?, drawing: InkDrawing) {
    if (drawing.strokes.isEmpty()) {
        if (previous != null) drawn.value = Drawn(entryId, previous, null)
        return
    }
    val svg = InkSvg.encode(drawing, ::svgColor).encodeToByteArray()
    val file = media.import(byteSourceOf(svg), InkSvg.MIME, "drawing.svg", now())
    val id = repo.attach(entryId, file, "drawing", drawing.width, drawing.height)
    drawn.value = Drawn(entryId, previous, id)
}

internal enum class DrawTool(val sizes: List<Float>) {
    Pen(listOf(4f, 8f, 16f)),
    Marker(listOf(24f, 40f, 64f)),
    Eraser(listOf(12f, 24f, 48f)),
}

/** The drawing being made, with its undo history; saved when the screen is left for good (not on rotation). */
internal class DrawingViewModel(private val services: AppServices, private val entryId: String, private val attachmentId: String?) :
    ViewModel() {
    var drawing by mutableStateOf(if (attachmentId == null) InkDrawing() else null)
        private set
    var tool by mutableStateOf(DrawTool.Pen)
    var color by mutableStateOf(InkDrawing.INK)
    var size by mutableStateOf(1)
    private val undone = ArrayDeque<InkDrawing>()
    private val redone = ArrayDeque<InkDrawing>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private var changed = false
    private var erasingFrom: InkDrawing? = null

    init {
        if (attachmentId != null) {
            viewModelScope.launch {
                val attachment = services.repo.entry(entryId).first()?.attachments?.firstOrNull { it.meta.id == attachmentId }
                drawing = attachment?.let { a ->
                    runCatching {
                        InkSvg.decode((services.media.localFile(a.meta.sha256) ?: services.media.file(a.meta.sha256)).readAllText())
                    }
                        .getOrNull()
                } ?: InkDrawing()
            }
        }
    }

    fun add(stroke: InkStroke) = drawing?.let { commit(it.copy(strokes = it.strokes + stroke)) }

    /** One eraser gesture is one undo step: [erase] while it moves, [endErase] when it lifts. */
    fun erase(x: Float, y: Float) {
        val now = drawing ?: return
        val radius = DrawTool.Eraser.sizes[size]
        val kept = now.strokes.filterNot { InkGeometry.hits(it, x, y, radius) }
        if (kept.size == now.strokes.size) return
        if (erasingFrom == null) erasingFrom = now
        drawing = now.copy(strokes = kept)
    }

    fun endErase() {
        val before = erasingFrom ?: return
        erasingFrom = null
        push(before)
    }

    fun moreRoom() = drawing?.let { commit(it.copy(height = it.height + InkDrawing.WIDTH / 2)) }

    fun undo() {
        val now = drawing ?: return
        val previous = undone.removeLastOrNull() ?: return
        redone.addLast(now)
        drawing = previous
        flags()
    }

    fun redo() {
        val now = drawing ?: return
        val next = redone.removeLastOrNull() ?: return
        undone.addLast(now)
        drawing = next
        flags()
    }

    private fun commit(next: InkDrawing) {
        drawing?.let(::push)
        drawing = next
    }

    private fun push(before: InkDrawing) {
        undone.addLast(before)
        if (undone.size > HISTORY) undone.removeFirst()
        redone.clear()
        changed = true
        flags()
    }

    private fun flags() {
        canUndo = undone.isNotEmpty()
        canRedo = redone.isNotEmpty()
    }

    override fun onCleared() {
        val result = drawing
        if (changed && result != null) services.scope.launch { services.saveDrawing(entryId, attachmentId, result) }
    }

    private companion object {
        const val HISTORY = 100
    }
}

/**
 * Drawing with a finger (7.5): one finger draws, two pan and zoom (a second finger cancels the line just begun). Pen
 * (width from speed), see-through marker, an eraser that takes whole strokes; colors are the palette's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrawingScreen(services: AppServices, nav: Navigator, route: Route.Drawing) {
    val vm = viewModel(key = "${route.entryId}/${route.attachmentId}") { DrawingViewModel(services, route.entryId, route.attachmentId) }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { BackButton(nav::back) },
            title = { Text(stringResource(Res.string.drawing_title)) },
            actions = {
                IconButton(vm::undo, enabled = vm.canUndo, modifier = Modifier.hand(vm.canUndo)) {
                    Icon(Icons.Undo, stringResource(Res.string.drawing_undo))
                }
                IconButton(vm::redo, enabled = vm.canRedo, modifier = Modifier.hand(vm.canRedo)) {
                    Icon(Icons.Redo, stringResource(Res.string.drawing_redo))
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val drawing = vm.drawing
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                if (drawing != null) Paper(vm, drawing)
            }
            Toolbar(vm)
        }
    }
}

@Composable
private fun Paper(vm: DrawingViewModel, drawing: InkDrawing) {
    val colors = MaterialTheme.colorScheme
    val theme = BenbowTheme.colors
    val cache = remember { InkShapes() }
    val shapes = remember(drawing) { cache.of(drawing.strokes) }
    var current by remember { mutableStateOf<InkStroke?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val unit = widthPx / drawing.width // px per canvas unit at zoom 1
        val canvasHeight = drawing.height * unit
        val grid = remember(drawing.width, drawing.height) {
            buildList {
                var y = DOT_STEP
                while (y < drawing.height) {
                    var x = DOT_STEP
                    while (x < drawing.width) {
                        add(Offset(x, y))
                        x += DOT_STEP
                    }
                    y += DOT_STEP
                }
            }
        }
        fun clamp(o: Offset, z: Float) = Offset(
            o.x.coerceIn(minOf(0f, widthPx - widthPx * z), 0f),
            o.y.coerceIn(minOf(0f, heightPx - canvasHeight * z), 0f),
        )
        val canvasLabel = stringResource(Res.string.drawing_canvas)
        Box(
            Modifier.fillMaxSize().semantics { contentDescription = canvasLabel }.pointerInput(drawing.width, drawing.height, widthPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.uptimeMillis
                    fun units(p: Offset) = (p - offset) / (zoom * unit)
                    val tool = vm.tool
                    val builder = InkStrokeBuilder(
                        if (tool ==
                            DrawTool.Marker
                        ) {
                            InkTool.Marker
                        } else {
                            InkTool.Pen
                        },
                        vm.color,
                        tool.sizes[vm.size],
                    )
                    var drawingLine = true
                    fun at(p: Offset, time: Long) {
                        val u = units(p)
                        if (tool == DrawTool.Eraser) vm.erase(u.x, u.y) else builder.add(u.x, u.y, (time - start).toFloat())
                    }
                    at(down.position, down.uptimeMillis)
                    if (tool != DrawTool.Eraser) current = builder.build()
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // Two fingers: pan and zoom about their middle; the line begun is dropped.
                            drawingLine = false
                            current = null
                            val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, 4f)
                            val centroid = event.calculateCentroid()
                            val moved = centroid - (centroid - offset) * (newZoom / zoom) + event.calculatePan()
                            zoom = newZoom
                            offset = clamp(moved, newZoom)
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (!drawingLine) continue
                        val change = pressed.first()
                        change.historical.forEach { at(it.position, it.uptimeMillis) }
                        at(change.position, change.uptimeMillis)
                        change.consume()
                        if (tool != DrawTool.Eraser) current = builder.build()
                    }
                    if (tool == DrawTool.Eraser) {
                        vm.endErase()
                    } else {
                        current?.takeIf { drawingLine && it.count > 0 }?.let(vm::add)
                        current = null
                    }
                }
            },
        ) {
            // What's drawn sits on its own cached layer (redrawn only when the drawing, zoom or position changes), so a
            // line being drawn costs one path a frame, not the whole page. Zoom is applied while drawing, not by scaling
            // the layer, so the cache stays sharp.
            val paper = colors.surfaceContainerLow
            val edge = colors.outlineVariant
            Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                withTransform({
                    translate(offset.x, offset.y)
                    scale(zoom, zoom, Offset.Zero)
                }) {
                    drawRect(paper, size = Size(widthPx, canvasHeight))
                    drawRect(edge, size = Size(widthPx, canvasHeight), style = Stroke(1.dp.toPx() / zoom))
                    // A faint dot grid helps keep lines straight; it isn't part of the drawing.
                    drawPoints(grid.map { it * unit }, PointMode.Points, edge, strokeWidth = 2.4f / zoom, cap = StrokeCap.Round)
                    drawInk(shapes, drawing.width, colors.onSurface, theme)
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val line = current ?: return@Canvas
                withTransform({
                    translate(offset.x, offset.y)
                    scale(zoom, zoom, Offset.Zero)
                }) { drawInk(listOf(InkShape(line)), drawing.width, colors.onSurface, theme) }
            }
        }
    }
}

@Composable
private fun Toolbar(vm: DrawingViewModel) {
    val colors = MaterialTheme.colorScheme
    val theme = BenbowTheme.colors
    Surface(color = colors.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolButton(vm, DrawTool.Pen, Icons.Edit, Res.string.drawing_pen)
                ToolButton(vm, DrawTool.Marker, Icons.Marker, Res.string.drawing_marker)
                ToolButton(vm, DrawTool.Eraser, Icons.Eraser, Res.string.drawing_eraser)
                Spacer(Modifier.width(8.dp))
                vm.tool.sizes.indices.forEach { i ->
                    val dot = (6 + i * 5).dp
                    Box(
                        Modifier.size(36.dp).hand().clickable { vm.size = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(dot).background(
                                if (vm.size ==
                                    i
                                ) {
                                    colors.primary
                                } else {
                                    colors.outline
                                },
                                CircleShape,
                            ),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(vm::moreRoom, modifier = Modifier.hand()) { Text(stringResource(Res.string.drawing_more_room)) }
            }
            Row(
                Modifier.sideScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (listOf(InkDrawing.INK) + BenbowPalette.tokens).forEach { token ->
                    val selected = vm.color == token && vm.tool != DrawTool.Eraser
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).border(if (selected) 3.dp else 0.dp, colors.primary, CircleShape)
                            .padding(5.dp).background(inkColor(token, colors.onSurface, theme), CircleShape)
                            .hand().clickable {
                                vm.color = token
                                if (vm.tool == DrawTool.Eraser) vm.tool = DrawTool.Pen
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    vm: DrawingViewModel,
    tool: DrawTool,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: org.jetbrains.compose.resources.StringResource,
) {
    IconToggleButton(vm.tool == tool, { vm.tool = tool }, modifier = Modifier.hand()) {
        Icon(
            icon,
            stringResource(label),
            tint = if (vm.tool ==
                tool
            ) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Canvas units between the grid's dots. */
private const val DOT_STEP = 50f
