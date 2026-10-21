package app.benbow.app

import app.benbow.client.ApiCache
import app.benbow.client.BenbowApi
import app.benbow.client.ByteSink
import app.benbow.client.ByteSource
import app.benbow.client.Connection
import app.benbow.client.DraftSession
import app.benbow.client.Due
import app.benbow.client.ExportProgress
import app.benbow.client.ExportReport
import app.benbow.client.HomeBlock
import app.benbow.client.IoDispatcher
import app.benbow.client.JournalRepository
import app.benbow.client.Media
import app.benbow.client.Pairing
import app.benbow.client.PairingException
import app.benbow.client.ReminderItem
import app.benbow.client.SearchQuery
import app.benbow.client.ServerHistory
import app.benbow.client.ServerPages
import app.benbow.client.ServerPurges
import app.benbow.client.ServerStorage
import app.benbow.client.Settings
import app.benbow.client.SpeechModel
import app.benbow.client.SyncController
import app.benbow.client.SyncEngine
import app.benbow.client.SyncResult
import app.benbow.client.Veil
import app.benbow.client.calendar.CalendarRepository
import app.benbow.client.calendar.CalendarSync
import app.benbow.client.calendar.CalendarSyncResult
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.calendar.moveJournalEvents
import app.benbow.client.db.BenbowDb
import app.benbow.client.moveToBoard
import app.benbow.client.moveToJournal
import app.benbow.client.removeAttachments
import app.benbow.client.upcoming
import app.benbow.core.api.PurgeDone
import app.benbow.core.api.PurgePreview
import app.benbow.core.api.PurgeRequest
import app.benbow.core.api.StorageReport
import app.benbow.core.api.UnreferencedFile
import app.benbow.core.media.AttachmentKey
import app.benbow.ui.editor.Removal
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** Rings reminders on this device; [schedule] replaces whatever was scheduled before. */
fun interface ReminderClock {
    fun schedule(due: List<Due>)
}

/** Makes this device's media store: a folder on Android and desktop, memory in the browser (ADR-029). */
fun interface MediaFactory {
    fun create(db: BenbowDb, apis: (Connection) -> BenbowApi, now: () -> Long): Media
}

/** Makes the speech model where the device can transcribe (Android, 7.6); [api] reaches the paired server. */
fun interface SpeechModelFactory {
    fun create(api: () -> BenbowApi): SpeechModel
}

/** A file just attached to [entryId]: an open editor of that entry places pictures and videos in its text. */
data class Attached(val entryId: String, val attachmentId: String, val kind: String)

/** Where the app should go when something outside opens it (a launcher shortcut, the widget, an .ics file). */
enum class AppLaunch { NewEntry, AnswerPrompt, CalendarImport }

/** The archive export, which runs in the app scope so leaving the screen doesn't stop it. */
sealed interface ExportState {
    data object Idle : ExportState

    data class Running(val progress: ExportProgress?) : ExportState

    data class Done(val report: ExportReport) : ExportState

    data class Failed(val reason: String) : ExportState
}

sealed interface AppMessage {
    data class AttachFailed(val reason: String) : AppMessage

    /** A track's file couldn't be fetched or played (ADR-035). */
    data object PlaybackFailed : AppMessage

    data class TranscriptionFailed(val reason: String) : AppMessage

    data object Copied : AppMessage

    data object Saved : AppMessage

    data class SaveFailed(val reason: String) : AppMessage

    /** A reminder rang where the system can't show it (the browser without permission for notifications). */
    data class Reminder(val text: String) : AppMessage

    /** An entry's veil changed (ADR-031); "Вернуть" puts [previous] back. */
    data class Veiled(val entryId: String, val veil: Veil?, val previous: Veil?) : AppMessage

    /** An entry was put off until [until] (null: brought back); "Вернуть" puts [previous] back. */
    data class Postponed(val entryId: String, val until: String?, val previous: String?) : AppMessage

    /** Бережно was switched from the Feed tab (a long press). */
    data class GentleSwitched(val on: Boolean) : AppMessage

    /** «Показать в ленте» of an entry the feed keeps out (quiet while Бережно is on, ADR-031). */
    data object HiddenFromFeed : AppMessage

    /** Tags went to the trash (7.9); "Вернуть" brings them back. */
    data class TagsTrashed(val ids: List<String>, val names: List<String>) : AppMessage

    /** Tags were merged into the one named [into]; "Вернуть" splits them off again. */
    data class TagsMerged(val ids: List<String>, val into: String) : AppMessage

    /** A category went to the trash; "Вернуть" brings it back. */
    data class CategoryTrashed(val id: String, val name: String) : AppMessage

    /** A block was taken off Home from its menu (ADR-034); "Вернуть" puts it back at [at]. */
    data class HomeBlockRemoved(val block: HomeBlock, val at: Int) : AppMessage

    /** A take's text is in (7.10): «Приложить запись» attaches its audio to [entryId] as a voice note. */
    data class Dictated(val take: TakeFile, val entryId: String) : AppMessage

    /** A take couldn't become text and was kept as a voice note. */
    data object DictationKept : AppMessage

    /** The текст key is locked: the speech model isn't on this device yet; «Открыть» goes to it. */
    data object DictationNeedsModel : AppMessage

    /** The microphone isn't allowed. */
    data object MicDenied : AppMessage

    /** Entries went onto the board (ADR-040); «Открыть» shows the first there. */
    data class MovedToBoard(val ids: List<String>) : AppMessage

    /** Notes went into the journal, the first on [day] (the day it was made); «Открыть» shows it in the feed. */
    data class MovedToJournal(val ids: List<String>, val day: String) : AppMessage

    /** Notes on the board went to its archive (ADR-040); "Вернуть" brings them back to their places. */
    data class BoardArchived(val ids: List<String>) : AppMessage

    /** Notes on the board went to the trash; "Вернуть" brings them back. */
    data class BoardTrashed(val ids: List<String>) : AppMessage

    /** A sound's transcript was let go; "Вернуть" puts [text] back. */
    data class TranscriptRemoved(val entryId: String, val attachmentId: String, val text: String) : AppMessage

    /** Attachments were taken off their entries into the trash (ADR-041, the library); "Вернуть" puts them back. */
    data class MediaTrashed(val keys: List<AttachmentKey>) : AppMessage

    /** Deleted for good (ADR-041): [bytes] go from the server once its quarantine ends, at [freesOn]. */
    data class Purged(val bytes: Long, val freesOn: Long) : AppMessage

    /** «Освободить на телефоне» let [bytes] go (they come back from the server when needed). */
    data class DeviceFreed(val bytes: Long) : AppMessage

    /** A voice note gave way to its text (7.10e); "Вернуть" brings it back. */
    data class VoiceReplaced(val entryId: String, val attachmentId: String) : AppMessage

    /** Something left the text in one tap or key (ADR-038: a card, a picture, a link); "Вернуть" undoes it there. */
    data class TextRemoved(val removal: Removal) : AppMessage
}

/**
 * Everything the screens need, created once per process by the platform app (it supplies the SQLite
 * drivers: the journal's file and the calendar's own, ADR-027). Writes run in [scope], which outlives
 * screens, so a closing editor still gets its commit.
 */
class AppServices(
    driver: SqlDriver,
    calendarDriver: SqlDriver,
    mediaStore: MediaFactory,
    val platform: String,
    val deviceName: String,
    /** Large media files kept on this device beyond what still has to reach the server. */
    internal val mediaCacheBytes: Long = 2L * 1024 * 1024 * 1024,
    /** Rings reminders on this device (Android alarms, a tray message on desktop); none in tests. */
    private val reminderClock: ReminderClock? = null,
    /** Speech to text on this device (7.6) and its model; none on desktop and in the browser. */
    transcriber: Transcriber? = null,
    speechModel: SpeechModelFactory? = null,
    /** The microphone for dictation (7.10); Android only. */
    mic: DictationMic? = null,
    /** What plays sounds here (ADR-035): Media3 in a session on Android, JavaFX on desktop, the page's <audio>. */
    audio: AudioEngine = SilentAudio(),
    /** Unfinished work shows: dev and bench builds and dev runs, never the owner's app (nothing behind it right now). */
    val labs: Boolean = false,
) {
    val scope = CoroutineScope(SupervisorJob() + IoDispatcher)
    val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }
    private val db = BenbowDb(driver)
    private val apis = ApiCache()
    val repo = JournalRepository(db, now, today = { today() })
    val pairing = Pairing(db)
    val history = ServerHistory(db, apis)

    /** Pages read on the server for link cards (the browser's way, ADR-038). */
    internal val pages = ServerPages(db, apis)

    /** «Место» (ADR-041): the server's count of its files, and the last one kept here. */
    internal val storage = ServerStorage(db, apis)

    /** How «Место» gets a fresh count; tests put their own here. */
    internal var storageReader: suspend () -> StorageReport = { storage.fetch() }

    /** How «Место» gets the files no entry references (older than thirty days); tests put their own here. */
    internal var unreferencedReader: suspend () -> List<UnreferencedFile> = { storage.unreferenced() }

    /** Deleting for good from the trash (ADR-041): the server's preview and commit; tests put their own here. */
    private val purging = ServerPurges(db, apis)
    internal var purgePreviewer: suspend (PurgeRequest) -> PurgePreview = { purging.preview(it) }
    internal var purgeCommitter: suspend (String) -> PurgeDone = { purging.commit(it) }

    /** After a purge: the snackbar, and a sync so this device takes the purged links and lets its copies go. */
    fun purged(done: PurgeDone) {
        messages.tryEmit(AppMessage.Purged(done.bytes, done.freesOn))
        sync.requestSync()
    }

    /** «Освободить на телефоне»: every large file the server holds and has backed up lets go here; the bytes freed. */
    suspend fun freeDevice(): Long = withContext(IoDispatcher) { media.evict(0) }.also { messages.tryEmit(AppMessage.DeviceFreed(it)) }

    /** Addresses whose pages are being read for their cards right now (they shimmer). */
    val linkLoading = MutableStateFlow<Set<String>>(emptySet())

    /** Pages asked for in this run, so a card isn't read twice at once or again after it failed now. */
    internal val triedPages: MutableSet<String> = HashSet()

    /** How a card's page is read (ADR-038); tests put their own pages here. */
    internal var pageReader: suspend (String) -> LinkPreview? = { readPage(this, it) }

    /** Where a tapped web link goes instead of the browser (tests); null: the platform's way. */
    internal var openUrlOverride: ((String) -> Unit)? = null
    val media: Media = mediaStore.create(db, apis, now)
    private val engine = SyncEngine(db, apis, now)
    private val calendarDb = CalendarDb(calendarDriver)
    val calendar = CalendarRepository(calendarDb, now)
    private val calendarSync = CalendarSync(calendarDb, now)

    /** Reading and importing a calendar file (7.3d). */
    internal val calendarImports = CalendarImports(calendar, scope)

    /** Opens a calendar file from outside (Android's "Открыть с помощью"): its preview comes up. */
    fun importCalendarFile(name: String, open: () -> ByteSource) {
        calendarImports.open(name, open)
        launch.value = AppLaunch.CalendarImport
    }

    /** The calendar's last sync, shown apart from the journal's: its trouble never fails the journal's sync. */
    val calendarStatus = MutableStateFlow<CalendarSyncResult?>(null)
    val sync = SyncController(
        scope = scope,
        sync = ::syncAll,
        pending = combine(repo.pendingCount(), calendar.pendingCount()) { journal, calendar -> journal + calendar },
        rejected = combine(repo.rejectedCount(), calendar.rejectedCount()) { journal, calendar -> journal + calendar },
        pendingMedia = media.pendingCount(),
        now = now,
    )
    val settings get() = Settings(db)

    val lock = AppLock(Settings(db), now)

    val gentle = Gentle(now)

    /** The app is back after five minutes away ([Gentle.onReturn]): a feed on screen starts at today again (ADR-036). */
    val cameBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The feed's filter (ADR-036 N5): it outlasts a switch of tabs, not the app; a new start shows the whole feed. */
    val feedFilter = MutableStateFlow(FeedFilter())

    /** «Показать в ленте» (ADR-036 N6): the entry the Feed opens at, among its neighbours; the Feed takes it. */
    val showInFeed = MutableStateFlow<String?>(null)

    /** The one player for every sound (ADR-035), its queue and the mini-player's state. */
    val player = AudioPlayer(media, audio) { messages.tryEmit(AppMessage.PlaybackFailed) }

    /** "Отложить до…" (ADR-031): quiet until [until], or back now (null); offers to take it back. */
    fun postpone(id: String, until: String?, previous: String?) {
        scope.launch {
            repo.setVeilUntil(id, until)
            messages.emit(AppMessage.Postponed(id, until, previous))
        }
    }

    /** Sets an entry's veil (ADR-031) and offers to take it back. */
    fun setVeil(id: String, veil: Veil?, previous: Veil?) {
        scope.launch {
            repo.setVeil(id, veil)
            messages.emit(AppMessage.Veiled(id, veil, previous))
        }
    }

    val appearance = MutableStateFlow(Appearance.load(Settings(db)))

    fun setAppearance(value: Appearance) {
        value.save(Settings(db))
        appearance.value = value
    }

    val export = MutableStateFlow<ExportState>(ExportState.Idle)

    /** Writes the whole journal as a ZIP ([writeArchive]) after committing any left-over drafts, so it holds what's on screen. */
    fun exportArchive(open: () -> ByteSink, withMedia: Boolean) {
        if (export.value is ExportState.Running) return
        export.value = ExportState.Running(null)
        scope.launch {
            export.value = try {
                repo.commitLeftoverDrafts()
                ExportState.Done(writeArchive(db, media, calendar, open(), withMedia, today()) { export.value = ExportState.Running(it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportState.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    /** Things the user must hear about (shown as a snackbar). */
    val messages = MutableSharedFlow<AppMessage>(extraBufferCapacity = 8)

    /** What the Search tab is to show when it opens next (a tag block's selection); it takes it and clears it. */
    val searchFor = MutableStateFlow<SearchQuery?>(null)

    /** Words for the board's search (ADR-040), asked from the journal's search: «Ещё N в записках». */
    val boardSearchFor = MutableStateFlow<String?>(null)

    /** A note to show on the board («Открыть» after a move): the board scrolls to it and it glows a moment. */
    val showOnBoard = MutableStateFlow<String?>(null)

    /** Journal entries onto the board (ADR-040): the same entries, one field each; a snackbar says where they went. */
    fun toBoard(ids: List<String>) {
        if (ids.isEmpty()) return
        scope.launch { ids.forEach { repo.moveToBoard(it) } }
        messages.tryEmit(AppMessage.MovedToBoard(ids))
    }

    /** Notes into the journal, on the days they were made (owner); [day] is the first one's, for the snackbar. */
    fun toJournal(ids: List<String>, day: String) {
        if (ids.isEmpty()) return
        scope.launch { ids.forEach { repo.moveToJournal(it) } }
        messages.tryEmit(AppMessage.MovedToJournal(ids, day))
    }

    /** Attachments off their entries into the trash, all at once (ADR-041); a snackbar offers them back. */
    fun trashMedia(keys: List<AttachmentKey>) {
        if (keys.isEmpty()) return
        scope.launch { repo.removeAttachments(keys) }
        messages.tryEmit(AppMessage.MediaTrashed(keys))
    }

    /** Set by the platform when it is opened for something; the app takes it and clears it. */
    val launch = MutableStateFlow<AppLaunch?>(null)

    val attached = MutableSharedFlow<Attached>(extraBufferCapacity = 16)

    /** Editors open now (main thread only): the browser commits them when the page hides and asks before closing. */
    val openDrafts = mutableSetOf<DraftSession>()

    /** Typing that hasn't reached the local DB yet. */
    val draftsPending: Boolean get() = openDrafts.any { it.pending }

    /** Saves every open editor's text as a revision now (the page is being hidden or closed). */
    fun commitOpenDrafts(): List<Job> = openDrafts.map { it.commit() }

    /** A drawing just saved (7.5): kept until the editor behind the drawing screen takes it. */
    val drawn = MutableStateFlow<Drawn?>(null)

    /** Voice notes to text (7.6), where this platform can; the model comes only from the paired server. */
    val transcriptions: Transcriptions? = if (transcriber != null && speechModel != null) {
        val model = speechModel.create { apis(Settings(db).connection ?: throw PairingException("Not paired with a server")) }
        Transcriptions(transcriber, model, repo, media, { Settings(db) }, scope) { messages.tryEmit(AppMessage.TranscriptionFailed(it)) }
            .also { t -> scope.launch { attached.collect(t::added) } }
    } else {
        null
    }

    /** Dictation (7.10): text from the microphone, where there is one and a recognizer (Android). */
    val dictations: Dictations? = if (mic != null && transcriber != null && transcriptions != null) {
        Dictations(this, mic, transcriber, transcriptions.model)
    } else {
        null
    }

    /** Voice notes brought back after «Заменить текстом» (7.10e): an open editor of their entry puts them in the text again. */
    val voiceBack = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** "Вернуть" for [AppMessage.TextRemoved]: the editor that removed it undoes it. */
    val removalBack = MutableSharedFlow<Removal>(extraBufferCapacity = 4)

    /** A sound named by hand: the title is its caption, artist and album go to its `track:` field. */
    internal fun nameSound(entryId: String, attachmentId: String, names: SoundNames) {
        scope.launch { repo.nameSound(entryId, attachmentId, names.title, names.artist, names.album) }
    }

    /** «Убрать расшифровку»: the sound keeps no text; the message offers it back. */
    internal fun removeTranscript(entryId: String, attachmentId: String, text: String) {
        scope.launch { repo.setTranscript(entryId, attachmentId, null) }
        messages.tryEmit(AppMessage.TranscriptRemoved(entryId, attachmentId, text))
    }

    /** "Вернуть" after «Заменить текстом»: the voice note is on its entry again, and back in the text where it's open. */
    fun restoreVoice(entryId: String, attachmentId: String) {
        scope.launch {
            repo.restoreAttachment(entryId, attachmentId)
            voiceBack.emit(attachmentId)
        }
    }

    /** Key combinations from the window (desktop): the app root and the open editor act on them. */
    val shortcuts = MutableSharedFlow<Shortcut>(extraBufferCapacity = 8)

    @Volatile
    private var reminders: List<ReminderItem> = emptyList()

    init {
        repo.commitLeftoverDrafts()
        scope.launch { moveJournalEvents(db, repo, calendar) }
        sync.start()
        scope.launch {
            repo.prepareSearch()
            repo.prepareSummaries()
        }
        if (reminderClock != null) {
            scope.launch {
                repo.reminders().collect {
                    reminders = it
                    rescheduleReminders()
                }
            }
        }
    }

    /** Puts the next rings on the clock; called on every change and after a repeating reminder rang. */
    fun rescheduleReminders() {
        reminderClock?.schedule(reminders.upcoming(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())))
    }

    /**
     * Unpairs. The calendar and the journal each refuse while something of theirs hasn't reached the server;
     * then both caches are cleared (they belong to that server's store).
     */
    fun forgetServer() {
        calendar.checkSynced()
        pairing.forget()
        calendar.forget()
    }

    /**
     * Text first (small, the outbox), then the calendar on its own channel (ADR-027), then files captured here;
     * a failed upload retries with backoff.
     */
    private suspend fun syncAll(): SyncResult {
        val result = engine.sync()
        if (result !is SyncResult.Done) return result
        Settings(db).connection?.let { calendarStatus.value = calendarSync.sync(apis(it), it) }
        return try {
            media.dropPurged() // before uploading: a file deleted for good is never sent
            media.uploadPending()
            media.evict(mediaCacheBytes)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Failed("upload: ${e.message ?: e::class.simpleName}")
        }
    }

    /** The journal day new entries belong to ([DAY_START_HOUR]). */
    fun today(): String = journalToday().toString()
}
