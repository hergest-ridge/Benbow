package app.benbow.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.benbow.app.resources.Res
import app.benbow.app.resources.conflict_banner
import app.benbow.app.resources.conflict_choose
import app.benbow.app.resources.entry_broken
import app.benbow.app.resources.history
import app.benbow.app.resources.in_trash_banner
import app.benbow.app.resources.menu
import app.benbow.app.resources.move_to_trash
import app.benbow.app.resources.restore
import app.benbow.client.DraftSession
import app.benbow.client.EntryDetail
import app.benbow.client.JournalRepository
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException
import app.benbow.core.content.toDocument
import app.benbow.core.content.toRichText
import app.benbow.core.text.RichText
import app.benbow.ui.editor.BenbowEditor
import app.benbow.ui.editor.EditorState
import app.benbow.ui.editor.EditorToolbar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
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

    private var shownHead: String? = null
    private var clean: String? = null

    init {
        if (route.entryId == null) show(DocumentCodec.decode(JournalRepository.emptyBody()).toRichText(), head = null)
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            session.entryIdFlow.filterNotNull().flatMapLatest { repo.entry(it) }.filterNotNull().collect(::onEntry)
        }
    }

    fun onEdited(text: RichText) = session.onChange(text)

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
    val openHistory: () -> Unit = {
        vm.commit()
        vm.entryId?.let { nav.open(Route.History(it)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton {
                        vm.commit()
                        nav.back()
                    }
                },
                title = { Text(dayLabel(detail?.day ?: route.day), style = MaterialTheme.typography.titleMedium) },
                actions = {
                    if (vm.entryId != null) {
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.More, stringResource(Res.string.menu)) }
                            DropdownMenu(menu, { menu = false }) {
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
        bottomBar = { if (editor != null) EditorToolbar(editor, Modifier.navigationBarsPadding().imePadding()) },
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
