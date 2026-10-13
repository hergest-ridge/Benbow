package app.benbow.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.attachments
import app.benbow.app.resources.background
import app.benbow.app.resources.background_none
import app.benbow.app.resources.comment_hint
import app.benbow.app.resources.comments
import app.benbow.app.resources.comments_count
import app.benbow.app.resources.conflict_banner
import app.benbow.app.resources.conflict_choose
import app.benbow.app.resources.copy_text
import app.benbow.app.resources.drop_to_attach
import app.benbow.app.resources.edit
import app.benbow.app.resources.entry_broken
import app.benbow.app.resources.favorite_add
import app.benbow.app.resources.favorite_remove
import app.benbow.app.resources.history
import app.benbow.app.resources.in_trash_banner
import app.benbow.app.resources.menu
import app.benbow.app.resources.move_to_trash
import app.benbow.app.resources.pin
import app.benbow.app.resources.print
import app.benbow.app.resources.restore
import app.benbow.app.resources.share
import app.benbow.app.resources.unpin
import app.benbow.client.DraftSession
import app.benbow.client.EntryDetail
import app.benbow.client.JournalRepository
import app.benbow.client.TemplateContext
import app.benbow.client.Templates
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException
import app.benbow.core.content.MarkdownExporter
import app.benbow.core.content.toDocument
import app.benbow.core.content.toPlainText
import app.benbow.core.content.toRichText
import app.benbow.core.text.Atom
import app.benbow.core.text.RichText
import app.benbow.ui.editor.BenbowEditor
import app.benbow.ui.editor.EditorState
import app.benbow.ui.editor.EditorToolbar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Holds the editor while the screen lives. Typing goes to a [DraftSession]; changes that arrive from
 * elsewhere (sync, a restored version) replace the text only while the user has no edits of their own
 * since the text was loaded or last committed, so nothing typed is ever overwritten on screen.
 */
internal class EditorViewModel(private val services: AppServices, route: Route.Editor) : ViewModel() {
    private val repo = services.repo
    private val session = DraftSession(repo, services.scope, route.entryId, route.day)

    var editor by mutableStateOf<EditorState?>(null)
        private set
    var detail by mutableStateOf<EntryDetail?>(null)
        private set
    var broken by mutableStateOf(false)
        private set
    val entryId: String? get() = session.entryId

    /** Reading a post (from the feed): nothing is editable until the menu says so. */
    var reading by mutableStateOf(route.readOnly && route.entryId != null)
        private set

    fun startEditing() {
        reading = false
    }

    private var shownHead: String? = null
    private var clean: String? = null

    init {
        if (route.entryId == null) show(DocumentCodec.decode(JournalRepository.emptyBody()).toRichText(), head = null)
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            session.entryIdFlow.filterNotNull().flatMapLatest { repo.entry(it) }.filterNotNull().collect(::onEntry)
        }
    }

    fun onEdited(text: RichText) {
        if (!reading) session.onChange(text)
    }

    /** Fills a new, still empty entry with a template; the draft starts right away. */
    fun applyTemplate(id: String, context: TemplateContext) {
        val template = Templates.byId(id) ?: return
        val text = DocumentCodec.decode(Templates.render(template, context)).toRichText()
        show(text, head = shownHead)
        session.onChange(text)
    }

    val isBlankNew: Boolean get() = entryId == null && editor?.model?.text?.isBlank() != false

    /** For attachments and metadata: a new entry is created on the first one. */
    suspend fun ensureEntryId(): String = session.ensureEntry()

    val knownTags = repo.tags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun withEntry(action: (String) -> Unit) {
        services.scope.launch { action(ensureEntryId()) }
    }

    fun toggleFavorite() = withEntry { repo.setFavorite(it, detail?.favorite != true) }

    fun togglePinned() = withEntry { repo.setPinned(it, detail?.pinned != true) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val commentCount = session.entryIdFlow.filterNotNull().flatMapLatest { repo.commentCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** What's on screen right now (typing included), as plain text or portable Markdown. */
    fun plainText(): String = editor?.model?.toDocument()?.toPlainText(includeSpoilers = true).orEmpty()

    fun markdown(): String = editor?.model?.toDocument()?.let { MarkdownExporter().export(it) }.orEmpty()

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

    fun setBackground(color: String?) = withEntry { repo.setBackground(it, color) }

    fun addTag(name: String) = withEntry { repo.addTag(it, name) }

    fun removeTag(name: String) = withEntry { repo.removeTag(it, name) }

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

    override fun onCleared() = commit()

    private fun onEntry(entry: EntryDetail) {
        detail = entry
        val current = editor
        if (current == null) {
            load(entry)
            return
        }
        if (entry.headRevision == shownHead) return
        val text = encode(current.model)
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
        val state = EditorState(text)
        editor = state
        shownHead = head
        clean = encode(state.model)
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
    var menu by remember { mutableStateOf(false) }
    var pickingBackground by remember { mutableStateOf(false) }
    var dropping by remember { mutableStateOf(false) }
    // The attachments ribbon: open by default unless every attachment already sits in the text; the clip toggles it.
    var ribbonChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val attachments = detail?.attachments.orEmpty()
    val inline = editor?.model?.paragraphs?.mapNotNullTo(HashSet()) { (it.atom as? Atom.Attachment)?.ref }.orEmpty()
    val ribbon = attachments.isNotEmpty() && (ribbonChoice ?: !attachments.all { it.meta.id in inline })
    LaunchedEffect(Unit) {
        services.shortcuts.collect {
            if (it == Shortcut.Save && !vm.reading) {
                vm.commit()
                services.messages.tryEmit(AppMessage.Saved)
            }
        }
    }
    // The whole page takes the note's color, as in Keep; the toolbars keep theirs.
    val noteColor = swatch(detail?.background)
    val comments by vm.commentCount.collectAsStateWithLifecycle()
    val copy = rememberCopy()
    val share = rememberShare()
    val print = rememberPrint()
    val scope = rememberCoroutineScope()
    val openHistory: () -> Unit = {
        vm.commit()
        vm.entryId?.let { nav.open(Route.History(it)) }
    }

    Scaffold(
        // Files dragged in from the desktop are attached like picked ones.
        modifier = Modifier.attachOnDrop(services, vm::ensureEntryId) { dropping = it },
        containerColor = noteColor ?: MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = noteColor ?: MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    BackButton {
                        vm.commit()
                        nav.back()
                    }
                },
                title = {
                    val label = if (vm.reading && detail != null) dateTimeLabel(detail.createdAt) else dayLabel(detail?.day ?: route.day)
                    Text(label, style = MaterialTheme.typography.titleMedium)
                },
                actions = {
                    if (attachments.isNotEmpty()) {
                        TextButton({ ribbonChoice = !ribbon }) {
                            val tint = if (ribbon) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            Icon(Icons.Attach, stringResource(Res.string.attachments), tint = tint, modifier = Modifier.size(20.dp))
                            Text(" ${attachments.size}", color = tint, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    val favorite = detail?.favorite == true
                    IconButton(vm::toggleFavorite) {
                        Icon(
                            if (favorite) Icons.Star else Icons.StarOutline,
                            stringResource(if (favorite) Res.string.favorite_remove else Res.string.favorite_add),
                            tint = if (favorite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    if (vm.entryId != null) {
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.More, stringResource(Res.string.menu)) }
                            DropdownMenu(menu, { menu = false }) {
                                if (vm.reading) {
                                    DropdownMenuItem(text = { Text(stringResource(Res.string.edit)) }, onClick = {
                                        menu = false
                                        vm.startEditing()
                                    })
                                }
                                DropdownMenuItem(text = {
                                    Text(
                                        stringResource(
                                            if (detail?.pinned ==
                                                true
                                            ) {
                                                Res.string.unpin
                                            } else {
                                                Res.string.pin
                                            },
                                        ),
                                    )
                                }, onClick = {
                                    menu = false
                                    vm.togglePinned()
                                })
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
                                    )
                                }
                                DropdownMenuItem(text = { Text(stringResource(Res.string.copy_text)) }, onClick = {
                                    menu = false
                                    if (!copy(vm.plainText())) vm.copied()
                                })
                                DropdownMenuItem(text = { Text(stringResource(Res.string.share)) }, onClick = {
                                    menu = false
                                    if (!share(vm.markdown())) vm.copied()
                                })
                                DropdownMenuItem(text = { Text(stringResource(Res.string.background)) }, onClick = {
                                    menu = false
                                    pickingBackground = true
                                })
                                DropdownMenuItem(text = { Text(stringResource(Res.string.print)) }, onClick = {
                                    menu = false
                                    vm.commit()
                                    val id = vm.entryId
                                    if (id != null) scope.launch { services.entryPage(id)?.let(print) }
                                })
                                DropdownMenuItem(text = { Text(stringResource(Res.string.history)) }, onClick = {
                                    menu = false
                                    openHistory()
                                })
                                if (detail?.inTrash != true) {
                                    DropdownMenuItem(text = { Text(stringResource(Res.string.move_to_trash)) }, onClick = {
                                        menu = false
                                        vm.moveToTrash()
                                        nav.back()
                                    })
                                }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (vm.reading) {
                QuickNoteBar(onSend = vm::comment, hint = stringResource(Res.string.comment_hint))
            } else if (editor != null) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    MediaBar(services, vm::ensureEntryId)
                    EditorToolbar(editor)
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
            if (pickingBackground) {
                SwatchPicker(
                    selected = detail?.background,
                    noneLabel = stringResource(Res.string.background_none),
                    onPick = {
                        vm.setBackground(it)
                        pickingBackground = false
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
                    onReact = vm::react,
                    ribbon = ribbon,
                    modifier = Modifier.fillMaxSize(),
                )
                return@Column
            }
            if (editor != null && vm.isBlankNew) {
                val context = templateContext(route.day)
                LaunchedEffect(route.template) { route.template?.let { vm.applyTemplate(it, context) } }
                TemplateRow { vm.applyTemplate(it, context) }
            }
            if (editor != null && !vm.broken) {
                val knownTags by vm.knownTags.collectAsStateWithLifecycle()
                MetadataRow(
                    mood = detail?.mood,
                    tags = detail?.tags.orEmpty(),
                    knownTags = knownTags,
                    onMood = vm::setMood,
                    onAddTag = vm::addTag,
                    onRemoveTag = vm::removeTag,
                )
            }
            if (ribbon && detail != null) {
                AttachmentStrip(services.media, attachments) { nav.open(Route.Media(detail.id, it.meta.id)) }
            }
            when {
                vm.broken -> Text(
                    stringResource(Res.string.entry_broken),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )

                editor != null -> {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(editor) { snapshotFlow { editor.model }.drop(1).collect(vm::onEdited) }
                    LaunchedEffect(Unit) { if (route.entryId == null) focus.requestFocus() }
                    BenbowEditor(
                        state = editor,
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focus)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}
