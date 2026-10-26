package app.benbow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.attachments
import app.benbow.app.resources.background_none
import app.benbow.app.resources.board_archive
import app.benbow.app.resources.board_hide_boxes
import app.benbow.app.resources.board_pin
import app.benbow.app.resources.board_show_boxes
import app.benbow.app.resources.board_text_hint
import app.benbow.app.resources.board_title_hint
import app.benbow.app.resources.board_to_journal
import app.benbow.app.resources.board_to_journal_day
import app.benbow.app.resources.board_unarchive
import app.benbow.app.resources.board_unpin
import app.benbow.app.resources.comment_hint
import app.benbow.app.resources.comments
import app.benbow.app.resources.comments_count
import app.benbow.app.resources.conflict_banner
import app.benbow.app.resources.conflict_choose
import app.benbow.app.resources.copy_text
import app.benbow.app.resources.drawing_add
import app.benbow.app.resources.drop_to_attach
import app.benbow.app.resources.edit
import app.benbow.app.resources.entry_broken
import app.benbow.app.resources.entry_to_board
import app.benbow.app.resources.favorite_add
import app.benbow.app.resources.favorite_remove
import app.benbow.app.resources.history
import app.benbow.app.resources.in_trash_banner
import app.benbow.app.resources.look_title
import app.benbow.app.resources.menu
import app.benbow.app.resources.move_to_trash
import app.benbow.app.resources.pin
import app.benbow.app.resources.print
import app.benbow.app.resources.restore
import app.benbow.app.resources.share
import app.benbow.app.resources.show_in_feed
import app.benbow.app.resources.unpin
import app.benbow.client.DraftSession
import app.benbow.client.EntryDetail
import app.benbow.client.GentleRules
import app.benbow.client.JournalRepository
import app.benbow.client.TagRef
import app.benbow.client.TemplateContext
import app.benbow.client.Templates
import app.benbow.client.addTag
import app.benbow.client.archiveOnBoard
import app.benbow.client.createBoardNote
import app.benbow.client.createTag
import app.benbow.client.effectiveVeil
import app.benbow.client.pinOnBoard
import app.benbow.client.removeTag
import app.benbow.client.setLook
import app.benbow.core.board.Board
import app.benbow.core.content.Checklists
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException
import app.benbow.core.content.MarkdownExporter
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import app.benbow.core.content.toDocument
import app.benbow.core.content.toPlainText
import app.benbow.core.content.toRichText
import app.benbow.core.text.Atom
import app.benbow.core.text.BlockKeys
import app.benbow.core.text.BlockStyle
import app.benbow.core.text.RichText
import app.benbow.ui.editor.BenbowEditor
import app.benbow.ui.editor.EditorState
import app.benbow.ui.editor.EditorToolbar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.CoroutineContext

/**
 * Holds the editor while the screen lives. Typing goes to a [DraftSession]; changes that arrive from
 * elsewhere (sync, a restored version) replace the text only while the user has no edits of their own
 * since the text was loaded or last committed, so nothing typed is ever overwritten on screen.
 */
internal class EditorViewModel(private val services: AppServices, route: Route.Editor) : ViewModel() {
    private val repo = services.repo
    private val board = route.board

    /**
     * A note on the board has its title apart, Keep's «Название» (owner): the document's first block when it is a
     * heading, shown in a field of its own; the editors hold the rest, and every save puts it back on top.
     */
    val title: TextFieldState? = if (board) TextFieldState() else null
    private var titleBlock: TextBlock? = null
    private var titleKey = BlockKeys().next()
    private var titleShown = ""
    private var titleEdited = false

    /** A note on the board that is a list shows in the list editor (ADR-040, K4); this is its document then. */
    var listDocument by mutableStateOf<Document?>(null)
        private set

    /** Goes up whenever the list is loaded anew (stored, synced, converted), so the list editor starts over. */
    var listVersion by mutableIntStateOf(0)
        private set
    private val session = DraftSession(
        repo,
        services.scope,
        route.entryId,
        route.day,
        create = if (route.board) { day, body -> repo.createBoardNote(day, body) } else { day, body -> repo.createEntry(day, body = body) },
    ).also { services.openDrafts += it }

    var editor by mutableStateOf<EditorState?>(null)
        private set
    var detail by mutableStateOf<EntryDetail?>(null)
        private set
    var broken by mutableStateOf(false)
        private set
    val entryId: String? get() = session.entryId
    val entryIdFlow get() = session.entryIdFlow

    /** Reading a post (from the feed): nothing is editable until the menu says so. */
    var reading by mutableStateOf(route.readOnly && route.entryId != null)
        private set

    /** Where a new drawing goes: after the text when the cursor wasn't in it as the brush was tapped. */
    var drawingAtEnd = true

    fun startEditing() {
        reading = false
    }

    private var shownHead: String? = null
    private var clean: String? = null

    init {
        if (route.entryId == null) show(DocumentCodec.decode(JournalRepository.emptyBody()).toRichText(), head = null)
        // «Список» from the + button: a list from the start, made on its first words.
        if (route.entryId == null && board && route.list) {
            listDocument = Checklists.toList(Document(emptyList()), titled = false)
            listVersion++
        }
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            session.entryIdFlow.filterNotNull().flatMapLatest { repo.entry(it) }.filterNotNull().collect(::onEntry)
        }
    }

    fun onEdited(text: RichText) {
        // While a list shows, the rich editor is out of sight: only the list's own changes count.
        if (!reading && listDocument == null) session.onChange(withTitle(text.toDocument()).toRichText())
    }

    fun listChanged(document: Document) {
        listDocument = document
        session.onChange(withTitle(document).toRichText())
    }

    /** The title's field changed: by the owner (saved), or by a load showing it (nothing to save). */
    fun titleChanged() {
        val words = title?.text?.toString() ?: return
        if (!titleEdited && words == titleShown) return
        titleEdited = true
        val body = body() ?: return
        session.onChange(withTitle(body).toRichText())
    }

    /** «Показать флажки»: each line a box (a note's title stays its title). */
    fun showBoxes() {
        val body = body() ?: return
        val list = Checklists.toList(body, titled = !board)
        listDocument = list
        listVersion++
        session.onChange(withTitle(list).toRichText())
    }

    /** «Скрыть флажки»: the boxes back to lines, in the rich editor. */
    fun hideBoxes() {
        val list = listDocument ?: return
        listDocument = null
        start(DocumentCodec.encode(withTitle(Checklists.toText(list))))
    }

    /** The whole document as it stands, title included. */
    fun currentDocument(): Document? = body()?.let(::withTitle)

    private fun body(): Document? = listDocument ?: editor?.model?.toDocument()

    /** [body] with the note's title on top again: its block as it came while its words stay, a new heading else. */
    private fun withTitle(body: Document): Document {
        val words = title?.text?.toString() ?: return body
        val head = when {
            words.isBlank() -> null
            titleBlock?.let(Checklists::words) == words -> titleBlock
            else -> TextBlock(titleKey, style = TITLE_STYLE, children = listOf(Span(words)))
        }
        return Document(listOfNotNull(head) + body.blocks)
    }

    /** Fills a new, still empty entry with a template; the draft starts right away. */
    fun applyTemplate(id: String, context: TemplateContext) {
        val template = Templates.byId(id) ?: return
        start(Templates.render(template, context))
    }

    /** Starts a new, still empty entry with [text] as its heading ("Написать об этом" from an event). */
    fun applyHeading(text: String) = start(Templates.heading(text))

    private fun start(body: String) {
        val text = DocumentCodec.decode(body).toRichText()
        show(text, head = shownHead)
        session.onChange(text)
    }

    val isBlankNew: Boolean get() = entryId == null && editor?.model?.text?.isBlank() != false && title?.text?.isBlank() != false

    /** For attachments and metadata: a new entry is created on the first one. */
    suspend fun ensureEntryId(): String = session.ensureEntry()

    private fun withEntry(action: (String) -> Unit) {
        services.scope.launch { action(ensureEntryId()) }
    }

    fun toggleFavorite() = withEntry { repo.setFavorite(it, detail?.favorite != true) }

    fun togglePinned() = withEntry { repo.setPinned(it, detail?.pinned != true) }

    /** A note on the board: pinned among the first ones, or not (ADR-040). */
    fun togglePinnedOnBoard() = withEntry { repo.pinOnBoard(it, detail?.fields?.get(Board.PIN) != "true") }

    /** A note in the board's archive back on the board, in its place. */
    fun unarchive() = withEntry { repo.archiveOnBoard(it, false) }

    /** The open note into the board's archive (Keep's button beside the pin), «Вернуть» on the snackbar. */
    fun archive() {
        commit()
        val id = entryId ?: return
        services.scope.launch { repo.archiveOnBoard(id, true) }
        services.messages.tryEmit(AppMessage.BoardArchived(listOf(id)))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val commentCount = session.entryIdFlow.filterNotNull().flatMapLatest { repo.commentCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** What's on screen right now (typing included), as plain text or portable Markdown. */
    fun plainText(): String = currentDocument()?.toPlainText(includeSpoilers = true).orEmpty()

    fun markdown(): String = currentDocument()?.let { MarkdownExporter().export(it) }.orEmpty()

    fun copied() = services.messages.tryEmit(AppMessage.Copied)

    @OptIn(ExperimentalCoroutinesApi::class)
    val comments = session.entryIdFlow.filterNotNull().flatMapLatest { repo.comments(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun comment(text: String) {
        if (text.isBlank()) return
        withEntry { repo.createComment(it, services.today(), text) }
    }

    fun react(emoji: String, on: Boolean) = withEntry { repo.toggleReaction(it, emoji, on) }

    fun setMood(mood: Int?) = withEntry { repo.setMood(it, mood) }

    fun moveTo(moment: LocalDateTime) = withEntry { repo.moveTo(it, moment) }

    fun moveAttachment(id: String, after: String?, before: String?) = withEntry { repo.moveAttachment(it, id, after, before) }

    fun setBackground(color: String?) = withEntry { repo.setBackground(it, color) }

    fun setLook(layers: Map<String, String?>) = withEntry { repo.setLook(it, layers) }

    fun addTag(tagId: String, name: String) = withEntry { repo.addTag(it, name, tagId) }

    /** A new tag from the picker's «Создать», straight onto this entry. */
    fun createTag(name: String, category: String?) =
        withEntry { entry -> repo.createTag(name, category)?.let { repo.addTag(entry, name, it) } }

    fun removeTag(tag: TagRef) = withEntry { repo.removeTag(it, tag.id) }

    fun commit() {
        session.commit()
    }

    fun moveToTrash() {
        commit()
        val id = entryId ?: return
        services.scope.launch { repo.moveToTrash(id) }
    }

    fun restoreFromTrash() {
        val id = entryId ?: return
        services.scope.launch { repo.restore(id) }
    }

    override fun onCleared() {
        alive = false
        services.openDrafts -= session
        commit()
    }

    private var alive = true

    /**
     * Reads the pages of the cards nobody read yet (ADR-038). Each page fills its card here, on [ui], or the stored entry
     * once the editor is gone; a card no longer in the text is left alone.
     */
    fun readCards(ui: CoroutineContext) {
        val state = editor?.takeIf { !reading } ?: return
        val cards = state.model.paragraphs.mapNotNull { p ->
            (p.atom as? Atom.LinkCard)?.takeIf { it.fetched == null }?.let { p.key to it }
        }
        if (cards.isEmpty()) return
        services.readCards(cards) { key, preview ->
            val picture = preview?.picture
            if (picture != null) {
                // A link that is a picture (ADR-045): attached, and under its link (or alone) where the card was.
                val keepLink = services.appearance.value.pictureLinks
                if (withContext(ui) { alive && editor?.hasCard(key) == true }) {
                    val ref = services.attachLinkPicture(ensureEntryId(), picture)
                    // The picture takes the card's place once the entry knows it, so its height never drops in between.
                    withTimeoutOrNull(KNOWN_MILLIS) {
                        snapshotFlow { detail?.attachments.orEmpty().any { it.meta.id == ref } }.first { it }
                    }
                    val placed = withContext(ui) { alive && editor?.cardToPicture(key, ref, keepLink) == true }
                    if (!placed) entryId?.let { services.placeStoredPicture(it, key, ref, keepLink) }
                } else {
                    val id = entryId ?: return@readCards
                    if (services.storedCard(
                            id,
                            key,
                        )
                    ) {
                        services.placeStoredPicture(id, key, services.attachLinkPicture(id, picture), keepLink)
                    }
                }
                return@readCards
            }
            val filled = withContext(ui) { alive && editor?.fillCard(key) { it.filled(preview, services.today()) } == true }
            if (!filled) entryId?.let { services.fillStoredCard(it, key, preview) }
        }
    }

    private fun onEntry(entry: EntryDetail) {
        detail = entry
        val current = editor
        if (current == null) {
            load(entry)
            return
        }
        if (entry.headRevision == shownHead) return
        val text = listDocument?.let(DocumentCodec::encode) ?: encode(current.model)
        when (text) {
            entry.body -> {
                shownHead = entry.headRevision
                clean = text
            }

            clean -> load(entry)
        }
    }

    private fun load(entry: EntryDetail) {
        val text = try {
            DocumentCodec.decode(entry.body).toRichText()
        } catch (_: DocumentFormatException) {
            broken = true
            return
        }
        show(text, entry.headRevision)
    }

    private fun show(text: RichText, head: String?) {
        val document = text.toDocument()
        // A note's title goes to its own field (board notes): the first block when it is a heading.
        val first = (document.blocks.firstOrNull() as? TextBlock)?.takeIf { board && it.listItem == null && it.style in TITLE_STYLES }
        val body = if (first != null) Document(document.blocks.drop(1)) else document
        titleBlock = first
        first?.let { titleKey = it.key }
        titleShown = first?.let(Checklists::words).orEmpty()
        titleEdited = false
        title?.setTextAndPlaceCursorAtEnd(titleShown)
        val state = EditorState(if (first != null) body.toRichText() else text)
        editor = state
        shownHead = head
        clean = DocumentCodec.encode(document)
        listDocument = body.takeIf { board && Checklists.isList(it) }
        listVersion++
    }

    private fun encode(text: RichText) = DocumentCodec.encode(text.toDocument())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScreen(services: AppServices, nav: Navigator, route: Route.Editor) {
    val vm = viewModel { EditorViewModel(services, route) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.commit() }
    val editor = vm.editor
    val detail = vm.detail
    val gentleRules by remember { services.repo.gentleRules() }.collectAsStateWithLifecycle(GentleRules.NONE)
    var menu by remember { mutableStateOf(false) }
    var pickingBackground by remember { mutableStateOf(false) }
    var postponing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    // «В дневник на другой день…» (ADR-040): the moment picked, then the note goes into the journal there.
    var toJournalOn by remember { mutableStateOf(false) }
    var guarding by remember { mutableStateOf(false) }
    if (moving) {
        val at = detail?.createdAt?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).time }
            ?: LocalTime(12, 0)
        MomentPicker(
            initial = LocalDateTime(LocalDate.parse(detail?.day ?: route.day), at),
            onPick = {
                moving = false
                vm.moveTo(it)
                if (toJournalOn) {
                    toJournalOn = false
                    vm.commit()
                    vm.entryId?.let { id -> services.toJournal(listOf(id), it.date.toString()) }
                    nav.back()
                }
            },
            onDismiss = {
                moving = false
                toJournalOn = false
            },
        )
    }
    var dropping by remember { mutableStateOf(false) }
    var editorFocused by remember { mutableStateOf(false) }
    // Dictation (7.10) writes at the cursor, or after the text when the editor isn't focused, like pictures land.
    val screenThread = rememberScreenThread()
    val dictation = remember(vm, screenThread) { EditorDictation(vm, screenThread) { editorFocused } }
    DisposableEffect(dictation) { onDispose { dictation.gone = true } }
    // «Заменить текстом» (7.10e): the voice note leaves the entry; "Вернуть" puts it back in the text.
    var replaced by remember { mutableStateOf<Pair<String, RichText>?>(null) }
    LaunchedEffect(Unit) {
        services.voiceBack.collect { id ->
            val state = vm.editor
            val last = replaced
            if (state == null || last?.first != id) return@collect
            if (state.model === last.second) state.undo() else state.insertAtom(Atom.Attachment(id), atEnd = true)
            replaced = null
        }
    }
    LaunchedEffect(Unit) {
        // A picture or video added while writing lands in the text: at the cursor, or after the text if it isn't there.
        services.attached.collect { added ->
            if (added.entryId == vm.entryId && !vm.reading && vm.listDocument == null && added.kind in INLINE_KINDS) {
                vm.editor?.insertAtom(Atom.Attachment(added.attachmentId), atEnd = !editorFocused)
            }
        }
    }
    LaunchedEffect(Unit) {
        // A drawing saved on the drawing screen goes in the text (where the cursor was), or replaces the one it redraws.
        services.drawn.collect { result ->
            if (result == null || result.entryId != vm.entryId) return@collect
            val state = vm.editor ?: return@collect
            val new = result.attachmentId
            when {
                result.previous == null && new != null -> state.insertAtom(Atom.Attachment(new), atEnd = vm.drawingAtEnd)
                result.previous != null && new != null -> state.replaceAttachment(result.previous, new)
                result.previous != null -> state.removeAttachment(result.previous)
            }
            // The old version stays on the server (files are never deleted); it just leaves this entry.
            result.previous?.let { old -> services.scope.launch { services.repo.removeAttachment(result.entryId, old) } }
            services.drawn.value = null
        }
    }
    // Links (ADR-038): cards read their pages as they appear; the link sheet; «Вернуть» after a removal.
    LaunchedEffect(editor, vm.reading) {
        val state = editor?.takeIf { !vm.reading } ?: return@LaunchedEffect
        snapshotFlow { state.model.paragraphs.count { (it.atom as? Atom.LinkCard)?.fetched == null } }
            .collect { if (it > 0) vm.readCards(screenThread) }
    }
    LaunchedEffect(editor) {
        val state = editor ?: return@LaunchedEffect
        snapshotFlow { state.removal }.filterNotNull().collect { services.messages.emit(AppMessage.TextRemoved(it)) }
    }
    LaunchedEffect(Unit) { services.removalBack.collect { vm.editor?.undoRemoval(it) } }
    editor?.linkRequest?.let { request ->
        LinkSheet(services, request, onDone = { mark, words -> editor.applyLink(request, mark, words) }, onDismiss = {
            editor.linkRequest =
                null
        })
    }
    // The attachments ribbon: open by default unless every attachment already sits in the text; the clip toggles it.
    var ribbonChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val attachments = detail?.attachments.orEmpty()
    // A list shows its pictures above its boxes, on the ribbon.
    val inline = if (vm.listDocument != null) {
        emptySet()
    } else {
        editor?.model?.paragraphs?.mapNotNullTo(HashSet()) { (it.atom as? Atom.Attachment)?.ref }.orEmpty()
    }
    val ribbon = attachments.isNotEmpty() && (ribbonChoice ?: !attachments.all { it.meta.id in inline })
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val transcriptStyle = MaterialTheme.typography.bodyMedium
    val transcripts = detail?.transcripts.orEmpty()
    val tracks = detail?.tracks.orEmpty()
    val pictures = remember(attachments, detail?.id, detail?.purged, transcripts, tracks, transcriptStyle, vm.reading) {
        EntryPictures(
            services,
            attachments,
            onOpen = { id ->
                detail?.id?.let { entry ->
                    val drawing = attachments.firstOrNull { it.meta.id == id }?.meta?.kind == "drawing"
                    nav.open(if (drawing && !vm.reading) Route.Drawing(entry, id) else Route.Media(entry, id))
                }
            },
            onRename = { id, names -> detail?.id?.let { entry -> services.nameSound(entry, id, names) } },
            entryId = detail?.id,
            purged = detail?.purged.orEmpty(),
            transcripts = transcripts,
            tracks = tracks,
            textHeight = { text, width ->
                val px = with(density) { width.roundToPx() }.coerceAtLeast(1)
                with(density) { measurer.measure(text, transcriptStyle, constraints = Constraints(maxWidth = px)).size.height.toDp() }
            },
            onReplace = if (vm.reading) {
                null
            } else {
                { id, text ->
                    val state = vm.editor
                    val entry = vm.entryId
                    if (state != null && entry != null && state.replaceAttachmentWithText(id, text)) {
                        replaced = id to state.model
                        services.scope.launch { services.repo.removeAttachment(entry, id) }
                        services.messages.tryEmit(AppMessage.VoiceReplaced(entry, id))
                    }
                }
            },
        )
    }
    LaunchedEffect(Unit) {
        services.shortcuts.collect {
            if (it == Shortcut.Save && !vm.reading) {
                vm.commit()
                services.messages.tryEmit(AppMessage.Saved)
            }
        }
    }
    if (guarding && detail != null) {
        FormerEntryDialog(
            count = 1,
            day = detail.day,
            onBack = {
                guarding = false
                vm.commit()
                services.toJournal(listOf(detail.id), detail.day)
                nav.back()
            },
            onDelete = {
                guarding = false
                vm.moveToTrash()
                nav.back()
            },
            onDismiss = { guarding = false },
        )
    }
    // The whole page takes the note's color, as in Keep; the toolbars keep theirs.
    val noteColor = swatch(detail?.background)
    // Opened on the board (ADR-040): the board's sheet needs the note's id once it has one, and its color.
    val boardStage = LocalBoardStage.current
    if (boardStage != null) {
        val id by vm.entryIdFlow.collectAsStateWithLifecycle()
        LaunchedEffect(id) { id?.let { boardStage.entryId = it } }
        LaunchedEffect(detail?.background, detail != null) { if (detail != null) boardStage.color = detail.background }
    }
    val comments by vm.commentCount.collectAsStateWithLifecycle()
    val copy = rememberCopy()
    val share = rememberShare()
    val print = rememberPrint()
    val scope = rememberCoroutineScope()
    val openHistory: () -> Unit = {
        vm.commit()
        vm.entryId?.let { nav.open(Route.History(it)) }
    }

    // The note's look over the whole page, bars and all, as its color is, in full and moving (ADR-047). Beside a list in a
    // wide window its light falls across the whole right side (PaneGlow): the page tells the pane its lights, and lays
    // its own out in the pane's place.
    val page = noteColor ?: if (route.board) boardCardColor() else MaterialTheme.colorScheme.background
    val glow = LocalPaneGlow.current
    var pageAt by remember { mutableStateOf(Offset.Zero) }
    if (glow != null && detail != null) {
        val drawn = remember(detail.look) { NoteLooks.look(detail.look) }
        val drift = drawn.motion == "drift"
        val dark = page.luminance() < 0.5f
        DisposableEffect(glow, detail.id, drawn.lights, drift, dark) {
            glow.show(detail.id, drawn.lights, drift, dark)
            onDispose { glow.hide(detail.id) }
        }
    }
    // The paper goes up with the words (owner: «эффект бумаги»), unless «Статичный фон»: reading, the whole post
    // scrolls; writing, the words under the title and tags.
    val readScroll = rememberScrollState()
    val writeScroll = rememberScrollState()
    var wordsTop by remember { mutableFloatStateOf(0f) }
    var wordsHeight by remember { mutableFloatStateOf(0f) }
    val words = if (vm.reading) readScroll else writeScroll
    val still = LocalAppearance.current.staticBackdrop
    val paperScroll = PaperScroll(
        offset = { if (still) 0f else words.value.toFloat() },
        length = { if (still) 0f else wordsTop + wordsHeight + words.maxValue },
        top = { wordsTop },
    )
    val measureWords = Modifier.onGloballyPositioned {
        wordsTop = it.positionInWindow().y - pageAt.y
        wordsHeight = it.size.height.toFloat()
    }
    val backdrop = detail?.let { entry ->
        entryBackdrop(
            entry.id,
            entry.look,
            page,
            muted = false,
            moving = true,
            lightFrame = glow?.let { g -> { g.frameAt(pageAt) } },
            paperScroll = paperScroll,
        )
    } ?: Modifier
    val looked = backdrop != Modifier
    val wide = LocalWide.current

    Scaffold(
        // Files dragged in from the desktop are attached like picked ones.
        modifier = Modifier.attachOnDrop(services, vm::ensureEntryId, onHover = { dropping = it }, onLink = { href ->
            vm.editor?.takeIf { !vm.reading }?.insertAtom(Atom.LinkCard(href), atEnd = !editorFocused)
        }).onGloballyPositioned { pageAt = it.positionInWindow() }.then(backdrop),
        // A note on the board: its card's color, the sheet it grew from (ADR-040). With a look, the look draws it.
        containerColor = if (looked) Color.Transparent else page,
        // A color outside the scheme has no content color of its own: the page's words and icons, as on the card.
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (looked) {
                        Color.Transparent
                    } else {
                        noteColor
                            ?: if (route.board) boardCardColor() else MaterialTheme.colorScheme.surface
                    },
                ),
                navigationIcon = {
                    BackButton {
                        vm.commit()
                        // On the board the note's sheet shrinks back into its card first (ADR-040).
                        boardStage?.close() ?: nav.back()
                    }
                },
                title = {
                    // The entry's date and time, the weekday under them; while editing, a tap moves it in time.
                    val (date, weekday) = dayTitle(detail?.day ?: route.day)
                    val label = detail?.let { "$date, ${timeLabel(it.createdAt)}" } ?: date
                    Column(if (vm.reading) Modifier else Modifier.clip(RoundedCornerShape(8.dp)).hand().clickable { moving = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            if (!vm.reading) Icon(Icons.ArrowDropDown, null, Modifier.size(20.dp)) // drawn: "▾" would need a web font
                        }
                        weekday?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (attachments.isNotEmpty()) {
                        TextButton({ ribbonChoice = !ribbon }, modifier = Modifier.hand()) {
                            val tint = if (ribbon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Icon(Icons.Attach, stringResource(Res.string.attachments), tint = tint, modifier = Modifier.size(20.dp))
                            Text(" ${attachments.size}", color = tint, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    // A note on the board, Keep's way: its pin beside the star, or the way out of the archive (ADR-040).
                    val boardNote = route.board || detail?.let { Board.onBoard(it.fields) } == true
                    if (boardNote && !vm.reading && detail?.inTrash != true) {
                        if (detail?.fields?.get(Board.ARCHIVED) == "true") {
                            IconButton(vm::unarchive, modifier = Modifier.hand()) {
                                Icon(Icons.Unarchive, stringResource(Res.string.board_unarchive))
                            }
                        } else {
                            val pinned = detail?.fields?.get(Board.PIN) == "true"
                            IconButton(vm::togglePinnedOnBoard, modifier = Modifier.hand()) {
                                Icon(
                                    if (pinned) Icons.Pin else Icons.PinOutline,
                                    stringResource(if (pinned) Res.string.board_unpin else Res.string.board_pin),
                                )
                            }
                            // Into the archive and away, as the trash does (owner: no way to it from an open note).
                            if (vm.entryId != null) {
                                IconButton({
                                    vm.archive()
                                    nav.back()
                                }, modifier = Modifier.hand()) { Icon(Icons.Archive, stringResource(Res.string.board_archive)) }
                            }
                        }
                    }
                    val favorite = detail?.favorite == true
                    IconButton(vm::toggleFavorite, modifier = Modifier.hand()) {
                        Icon(
                            if (favorite) Icons.Star else Icons.StarOutline,
                            stringResource(if (favorite) Res.string.favorite_remove else Res.string.favorite_add),
                            tint = if (favorite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    // «Оформление записки» beside the star while writing (owner); reading, the note is only read.
                    if (!vm.reading) {
                        IconButton({ pickingBackground = true }, modifier = Modifier.hand()) {
                            Icon(Icons.Palette, stringResource(Res.string.look_title))
                        }
                    }
                    if (vm.entryId != null) {
                        Box {
                            IconButton({ menu = true }, modifier = Modifier.hand()) { Icon(Icons.More, stringResource(Res.string.menu)) }
                            DropdownMenu(menu, { menu = false }) {
                                // Between the journal and the board (ADR-040): the same entry, one field; a note goes to
                                // the journal on the day it was made, or on another day picked for it.
                                if (detail != null && !detail.inTrash && !vm.reading) {
                                    if (Board.onBoard(detail.fields)) {
                                        DropdownMenuItem(text = { Text(stringResource(Res.string.board_to_journal)) }, onClick = {
                                            menu = false
                                            vm.commit()
                                            services.toJournal(listOf(detail.id), detail.day)
                                            nav.back()
                                        }, modifier = Modifier.hand())
                                        DropdownMenuItem(text = { Text(stringResource(Res.string.board_to_journal_day)) }, onClick = {
                                            menu = false
                                            toJournalOn = true
                                            moving = true
                                        }, modifier = Modifier.hand())
                                    } else if (detail.kind !in NOT_IN_FEED) {
                                        DropdownMenuItem(text = { Text(stringResource(Res.string.entry_to_board)) }, onClick = {
                                            menu = false
                                            vm.commit()
                                            services.toBoard(listOf(detail.id))
                                            nav.back()
                                        }, modifier = Modifier.hand())
                                    }
                                }
                                // A note on the board: its lines as boxes and back, as Keep's «Показать флажки».
                                if (route.board && !vm.reading) {
                                    val list = vm.listDocument != null
                                    if (list || vm.currentDocument()?.let(Checklists::canBeList) == true) {
                                        DropdownMenuItem(text = {
                                            Text(stringResource(if (list) Res.string.board_hide_boxes else Res.string.board_show_boxes))
                                        }, onClick = {
                                            menu = false
                                            if (list) vm.hideBoxes() else vm.showBoxes()
                                        }, modifier = Modifier.hand())
                                    }
                                }
                                if (vm.reading) {
                                    DropdownMenuItem(text = { Text(stringResource(Res.string.edit)) }, onClick = {
                                        menu = false
                                        vm.startEditing()
                                    }, modifier = Modifier.hand())
                                }
                                if (!route.board) {
                                    DropdownMenuItem(text = {
                                        Text(stringResource(if (detail?.pinned == true) Res.string.unpin else Res.string.pin))
                                    }, onClick = {
                                        menu = false
                                        vm.togglePinned()
                                    }, modifier = Modifier.hand())
                                }
                                if (!vm.reading) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (comments >
                                                    0
                                                ) {
                                                    stringResource(Res.string.comments_count, comments.toInt())
                                                } else {
                                                    stringResource(Res.string.comments)
                                                },
                                            )
                                        },
                                        onClick = {
                                            menu = false
                                            vm.commit()
                                            vm.entryId?.let { nav.open(Route.Comments(it)) }
                                        },
                                        modifier = Modifier.hand(),
                                    )
                                }
                                DropdownMenuItem(text = { Text(stringResource(Res.string.copy_text)) }, onClick = {
                                    menu = false
                                    if (!copy(vm.plainText())) vm.copied()
                                }, modifier = Modifier.hand())
                                DropdownMenuItem(text = { Text(stringResource(Res.string.share)) }, onClick = {
                                    menu = false
                                    if (!share(vm.markdown())) vm.copied()
                                }, modifier = Modifier.hand())
                                val ruled = detail?.let { gentleRules.ruled(it.day, it.tagIds) }
                                VeilMenuItems(
                                    effectiveVeil(detail?.ownVeil, ruled),
                                    ruled,
                                ) { veil ->
                                    menu = false
                                    vm.entryId?.let { services.setVeil(it, veil, detail?.ownVeil) }
                                }
                                PostponeMenuItem(detail?.until) { choose ->
                                    menu = false
                                    if (choose) postponing = true else vm.entryId?.let { services.postpone(it, null, detail?.until) }
                                }
                                if (detail != null && detail.kind !in NOT_IN_FEED && !detail.inTrash) {
                                    DropdownMenuItem(text = { Text(stringResource(Res.string.show_in_feed)) }, onClick = {
                                        menu = false
                                        vm.commit()
                                        services.showInFeed.value = detail.id
                                        nav.selectTab(Route.Feed)
                                    }, modifier = Modifier.hand())
                                }
                                DropdownMenuItem(text = { Text(stringResource(Res.string.print)) }, onClick = {
                                    menu = false
                                    vm.commit()
                                    val id = vm.entryId
                                    if (id != null) scope.launch { services.entryPage(id)?.let(print) }
                                }, modifier = Modifier.hand())
                                DropdownMenuItem(text = { Text(stringResource(Res.string.history)) }, onClick = {
                                    menu = false
                                    openHistory()
                                }, modifier = Modifier.hand())
                                if (detail?.inTrash != true) {
                                    DropdownMenuItem(text = { Text(stringResource(Res.string.move_to_trash)) }, onClick = {
                                        menu = false
                                        // A note that was a journal entry: the journal back first (ADR-040).
                                        if (detail != null && Board.fromJournal(detail.fields)) {
                                            guarding = true
                                        } else {
                                            vm.moveToTrash()
                                            nav.back()
                                        }
                                    }, modifier = Modifier.hand())
                                }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (vm.reading) {
                Column {
                    // A post on a phone has no tab bar, so it shows what plays itself (wide windows have the app's strip).
                    val openPlayer = LocalOpenPlayer.current
                    if (!LocalWide.current && openPlayer != null) MiniPlayer(services, openPlayer, bottomInset = false)
                    // In a wide window the field is in the thread, under the comments (owner).
                    if (!wide) QuickNoteBar(services, onSend = vm::comment, hint = stringResource(Res.string.comment_hint))
                }
            } else if (editor != null) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    MediaBar(services, vm::ensureEntryId, dictation.takeIf { vm.listDocument == null }) {
                        IconButton({
                            vm.drawingAtEnd = !editorFocused
                            services.scope.launch { vm.ensureEntryId().let { id -> nav.open(Route.Drawing(id, null)) } }
                        }, modifier = Modifier.hand()) { Icon(Icons.Brush, stringResource(Res.string.drawing_add)) }
                    }
                    // On the board a checklist is the note's own list (Keep's «Показать флажки»), never boxes in its text.
                    if (vm.listDocument == null) EditorToolbar(editor, onChecklist = vm::showBoxes.takeIf { route.board })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (detail?.conflictHeads?.isNotEmpty() == true) {
                Banner(
                    stringResource(Res.string.conflict_banner),
                    stringResource(Res.string.conflict_choose),
                    error = true,
                    onAction = openHistory,
                )
            }
            if (detail?.inTrash == true) {
                Banner(stringResource(Res.string.in_trash_banner), stringResource(Res.string.restore), onAction = vm::restoreFromTrash)
            }
            if (dropping) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        stringResource(Res.string.drop_to_attach),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (postponing) {
                PostponeDialog(onPick = { day ->
                    postponing = false
                    vm.entryId?.let { services.postpone(it, day.toString(), detail?.until) }
                }, onDismiss = { postponing = false })
            }
            if (pickingBackground) {
                val words = remember { vm.plainText() }
                LookSheet(
                    id = detail?.id.orEmpty(),
                    words = words,
                    color = detail?.background,
                    look = detail?.look.orEmpty(),
                    page = if (route.board) boardCardColor() else MaterialTheme.colorScheme.background,
                    onColor = vm::setBackground,
                    onLook = vm::setLook,
                    onDismiss = { pickingBackground = false },
                )
            }
            if (vm.reading && editor != null && detail != null && !vm.broken) {
                val thread by vm.comments.collectAsStateWithLifecycle()
                PostView(
                    services,
                    detail,
                    editor,
                    thread,
                    onOpenMedia = { nav.open(Route.Media(detail.id, it)) },
                    onOpenComment = { nav.open(Route.Editor(it.summary.id, it.summary.day)) },
                    onOpenCommentMedia = { comment, attachment -> nav.open(Route.Media(comment, attachment)) },
                    onReact = vm::react,
                    ribbon = ribbon,
                    pictures = pictures,
                    scroll = readScroll,
                    commentField = if (wide) {
                        { QuickNoteBar(services, onSend = vm::comment, hint = stringResource(Res.string.comment_hint), inline = true) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxSize().then(measureWords),
                )
                return@Column
            }
            if (editor != null && vm.isBlankNew && !route.board) {
                val context = templateContext(route.day)
                LaunchedEffect(route.template) { route.template?.let { vm.applyTemplate(it, context) } }
                LaunchedEffect(route.heading) { route.heading?.let(vm::applyHeading) }
                TemplateRow { vm.applyTemplate(it, context) }
            }
            if (editor != null && !vm.broken && !route.board) {
                MetadataRow(
                    services,
                    mood = detail?.mood,
                    tags = detail?.tags.orEmpty(),
                    onMood = vm::setMood,
                    onAddTag = vm::addTag,
                    onCreateTag = vm::createTag,
                    onRemoveTag = vm::removeTag,
                    withMood = !route.board,
                )
            }
            if (ribbon && detail != null) {
                AttachmentStrip(
                    services.media,
                    attachments,
                    onOpen = { nav.open(Route.Media(detail.id, it.meta.id)) },
                    placed = inline,
                    onMove = vm::moveAttachment,
                )
            }
            // A note's title on top (Keep's «Название»), then its words; its tags at the foot.
            val title = vm.title
            if (title != null && editor != null && !vm.broken && !vm.reading) {
                LaunchedEffect(title) { snapshotFlow { title.text.toString() }.drop(1).collect { vm.titleChanged() } }
                NoteTitle(title)
            }
            val fill = if (route.board) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxSize()
            when {
                vm.broken -> Text(
                    stringResource(Res.string.entry_broken),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )

                editor != null && vm.listDocument != null -> key(vm.listVersion) {
                    ChecklistEditor(vm.listDocument!!, vm::listChanged, fill, focusFirst = route.entryId == null)
                }

                editor != null -> {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(editor) { snapshotFlow { editor.model }.drop(1).collect(vm::onEdited) }
                    LaunchedEffect(Unit) { if (route.entryId == null) focus.requestFocus() }
                    Box(fill) {
                        // A note's empty words say what goes there, as Keep's «Заметка»: over a plain empty line only
                        // (a list's marker or a heading there would show through it).
                        val plain = editor.model.paragraphs.singleOrNull()?.let {
                            it.list == null && it.style == BlockStyle.Normal && it.callout == null && it.atom == null
                        } == true
                        if (route.board && plain && editor.model.text.isEmpty()) {
                            Text(
                                stringResource(Res.string.board_text_hint),
                                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BenbowEditor(
                            state = editor,
                            atoms = pictures,
                            modifier = Modifier
                                .fillMaxSize()
                                .onFocusChanged { editorFocused = it.hasFocus }
                                .focusRequester(focus)
                                .then(measureWords)
                                .verticalScroll(writeScroll)
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                                .fillMaxWidth(),
                        )
                    }
                }
            }
            if (route.board && editor != null && !vm.broken) {
                MetadataRow(
                    services,
                    mood = null,
                    tags = detail?.tags.orEmpty(),
                    onMood = vm::setMood,
                    onAddTag = vm::addTag,
                    onCreateTag = vm::createTag,
                    onRemoveTag = vm::removeTag,
                    withMood = false,
                )
            }
        }
    }
}

/**
 * Dictation into the editor (7.10): each phrase goes in as typed text at the cursor, or after the text when the editor
 * isn't [focused]; ✕ takes the take's phrases back while nothing else was typed. A take makes the entry when it begins,
 * so one that has to stay a voice note has its entry.
 */
private class EditorDictation(private val vm: EditorViewModel, ui: CoroutineContext, private val focused: () -> Boolean) :
    ScreenTarget(ui) {
    private var count = 0
    private var after: RichText? = null

    override suspend fun begin(): String {
        count = 0
        after = null
        return vm.ensureEntryId()
    }

    override fun put(phrase: String): Boolean {
        val editor = vm.editor?.takeIf { !vm.reading } ?: return false
        editor.insertDictated(phrase, atEnd = !focused())
        count++
        after = editor.model
        return true
    }

    override suspend fun takeBack() = onScreen {
        val editor = vm.editor ?: return@onScreen
        if (editor.model === after) repeat(count) { editor.undo() }
    }
}

/** Attachments that go into the text when added while writing (pictures, videos, sounds); files stay in the ribbon. */
private val INLINE_KINDS = setOf("image", "video", "voice", "audio")

/** Kinds the feed never shows: «Показать в ленте» isn't offered for them (ADR-036 N6). */
private val NOT_IN_FEED = setOf("comment", "system", "event", "reminder")

/** A note's title is a heading of this level; one of any level read from elsewhere shows as its title too. */
private const val TITLE_STYLE = "h3"

/** How long a link's picture waits for the entry to know it before it takes its card's place anyway. */
private const val KNOWN_MILLIS = 2_000L
private val TITLE_STYLES = setOf("h1", "h2", "h3")

/** Keep's «Название»: a field of its own, a heading's size; Enter goes on to the words under it. */
@Composable
private fun NoteTitle(state: TextFieldState) {
    val focus = LocalFocusManager.current
    val onward = remember(state) {
        InputTransformation {
            if ('\n' in asCharSequence()) {
                replace(0, length, asCharSequence().toString().replace("\n", ""))
                focus.moveFocus(FocusDirection.Down)
            }
        }
    }
    val hint = stringResource(Res.string.board_title_hint)
    BasicTextField(
        state,
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
        inputTransformation = onward,
        textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorator = TextFieldDecorator { field ->
            Box {
                if (state.text.isEmpty()) {
                    Text(hint, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                field()
            }
        },
    )
}
