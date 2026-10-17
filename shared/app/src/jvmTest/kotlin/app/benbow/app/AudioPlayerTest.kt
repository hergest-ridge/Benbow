package app.benbow.app

import app.benbow.client.EntrySummary
import app.benbow.client.FeedItem
import app.benbow.client.LocalFile
import app.benbow.client.SearchQuery
import app.benbow.client.Veil
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.benbow.core.audio.TrackTags
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.Hlc
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sounds from elsewhere and the one player (ADR-035): attaching reads tags and the cover; the queue goes as it should. */
class AudioPlayerTest {
    private val engine = FakeEngine()
    private val messages = CopyOnWriteArrayList<AppMessage>()
    private val names = TrackNames("Голосовое сообщение", "Аудио")

    @Test
    fun aTaggedMp3AttachesWithItsNamesCoverAndLength() = runBlocking {
        val services = services()
        val entry = services.repo.createNote(services.today(), "")
        val bytes = TestSounds.mp3("Кукушка", "Кино", cover = TestSounds.jpeg(1400, 1400))
        services.attach({ entry }, { bytes.inputStream() }) { MediaProbe("audio/mpeg", "01 - kukushka.mp3", "audio") }.join()
        val detail = services.repo.entry(entry).first()!!
        val sound = detail.attachments.single()
        assertEquals(TrackTags("Кукушка", "Кино"), detail.tracks[sound.meta.id])
        assertEquals(1000L * 1152 * 1000 / 44_100, sound.meta.durationMs, "the length from the Xing header")
        val cover = assertNotNull(sound.meta.previewSha256, "the embedded cover is the preview")
        val picture = ImageIO.read(services.media.localFile(cover)!!)
        assertEquals(COVER_SIZE, picture.width, "covers are kept large enough for the player")
        assertTrue(services.repo.untranscribedIn(entry).isEmpty(), "music isn't offered for transcription")
        val found = services.repo.search(SearchQuery("кино")).first()
        assertEquals(listOf(entry), found.map { it.id }, "the artist is searchable")
        val track = names.track(entry, sound, detail.tracks[sound.meta.id])
        assertEquals("Кукушка", track.title)
        assertEquals("Кино", track.artist)
    }

    @Test
    fun aSoundWithoutTagsKeepsItsFileNameAndStaysTranscribable() = runBlocking {
        val services = services()
        val entry = services.repo.createNote(services.today(), "")
        val bytes = TestSounds.MPEG_FRAMES
        services.attach({
            entry
        }, { bytes.inputStream() }) { MediaProbe(refineMime("application/octet-stream", "Запись.opus"), "Запись.opus", "audio") }
            .join()
        val detail = services.repo.entry(entry).first()!!
        assertNull(detail.tracks.values.firstOrNull())
        assertEquals("Запись", names.track(entry, detail.attachments.single(), null).title)
        assertEquals("audio/ogg", detail.attachments.single().meta.mime, "known by its extension")
        assertEquals(1, services.repo.untranscribedIn(entry).size)
    }

    @Test
    fun theQueuePlaysOnAndStopsPausedOnTheLastTrack() = runBlocking {
        val services = services()
        val (a, b) = twoTracks(services)
        services.player.toggle(a) { listOf(a, b) }
        eventually { engine.loaded == a.id }
        assertTrue(engine.playing)
        engine.report(playing = true, position = 1_000)
        assertEquals(a, services.player.state.value?.track)
        engine.end()
        eventually { engine.loaded == b.id }
        engine.report(playing = true, position = 500)
        engine.end()
        eventually { services.player.state.value?.playing == false }
        val last = services.player.state.value!!
        assertEquals(b, last.track, "it stays on the last track")
        assertEquals(0L, last.positionMs)
        assertEquals(0L, engine.seekedTo)
    }

    @Test
    fun theSameTrackPausesAndGoesOnAndBackRestartsOrSteps() = runBlocking {
        val services = services()
        val (a, b) = twoTracks(services)
        services.player.toggle(b) { listOf(a, b) }
        eventually { engine.loaded == b.id }
        engine.report(playing = true, position = 10_000)
        services.player.toggle(b) { error("not asked again for the current track") }
        assertFalse(engine.playing)
        engine.report(playing = false, position = 10_000)
        services.player.previous()
        assertEquals(0L, engine.seekedTo, "past three seconds, back restarts the track")
        engine.report(playing = true, position = 1_000)
        services.player.previous()
        eventually { engine.loaded == a.id }
        services.player.next()
        eventually { engine.loaded == b.id }
        services.player.close()
        assertNull(services.player.state.value)
        assertTrue(engine.stopped)
    }

    @Test
    fun aFileThatCantBeHadFailsWithAMessage() = runBlocking {
        val services = services()
        val missing = Track("x", null, "0".repeat(64), "audio/mpeg", "Нет файла")
        services.player.toggle(missing) { listOf(missing) }
        eventually { services.player.state.value?.failed == true }
        eventually { AppMessage.PlaybackFailed in messages }
        assertNull(engine.loaded)
    }

    @Test
    fun whatTheEngineCantPlayGoesOutside() = runBlocking {
        val services = services()
        val (a, _) = twoTracks(services, mime = "audio/flac")
        engine.plays = { it != "audio/flac" }
        services.player.toggle(a) { listOf(a) }
        eventually { engine.outside == a.sha256 }
        assertNull(engine.loaded)
        assertEquals(false, services.player.state.value?.playing)
    }

    @Test
    fun theFeedsQueueRunsDownToOlderPostsAndSkipsCurtains() {
        fun post(id: String, veil: Veil? = null, vararg sounds: String) = FeedRow.Item(
            FeedItem(
                EntrySummary(id, "2026-10-17", 0, id, "", false, veil = veil),
                "post",
                "",
                emptySet(),
                audio = sounds.map {
                    AttachmentState(AttachmentMeta(it, "sha-$it", 1, "audio/mpeg", "audio", "$it.mp3"), 1.0, hlc = Hlc.ZERO)
                },
            ),
        )
        val rows = listOf(
            FeedRow.Month("2026-10"),
            post("new", null, "n1"),
            FeedRow.Day("2026-10-17"),
            post("start", Veil.Curtain, "s1", "s2"),
            post("curtained", Veil.Curtain, "c1"),
            post("older", null, "o1"),
        )
        assertEquals(listOf("s1", "s2", "o1"), names.feedQueue(rows, "start", gentle = true).map { it.id })
        assertEquals(listOf("s1", "s2", "c1", "o1"), names.feedQueue(rows, "start", gentle = false).map { it.id })
        assertEquals(emptyList(), names.feedQueue(rows, "gone", gentle = true))
    }

    /** Two sounds attached to a note, as the note's queue. */
    private suspend fun twoTracks(services: AppServices, mime: String = "audio/mpeg"): Pair<Track, Track> {
        val entry = services.repo.createNote(services.today(), "")
        for ((name, bytes) in listOf("a.mp3" to TestSounds.MPEG_FRAMES, "b.mp3" to TestSounds.MPEG_FRAMES + byteArrayOf(1))) {
            services.attach({ entry }, { bytes.inputStream() }) { MediaProbe(mime, name, "audio") }.join()
        }
        val detail = services.repo.entry(entry).first()!!
        val (a, b) = names.queue(entry, detail.attachments, detail.tracks)
        return a to b
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(10)
    }

    private fun services(): AppServices = AppServices(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
        mediaIn(Files.createTempDirectory("benbow-media").toFile()),
        "android",
        "Test phone",
        audio = engine,
    ).also { services ->
        CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) { services.messages.collect { messages += it } }
    }

    /** Remembers what it was asked; the test tells what it "hears" back. */
    private class FakeEngine : AudioEngine {
        override var events: AudioEvents? = null

        @Volatile var loaded: String? = null

        @Volatile var playing = false

        @Volatile var seekedTo: Long? = null

        @Volatile var stopped = false

        @Volatile var outside: String? = null
        var plays: (String) -> Boolean = { true }
        private var track: Track? = null

        override fun load(file: LocalFile, track: Track, cover: LocalFile?) {
            check(file.exists())
            this.track = track
            loaded = track.id
        }

        override fun play() {
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun seek(positionMs: Long) {
            seekedTo = positionMs
        }

        override fun stop() {
            stopped = true
            loaded = null
        }

        override fun canPlay(mime: String) = plays(mime)

        override fun openOutside(file: LocalFile, mime: String) {
            outside = file.name
        }

        fun report(playing: Boolean, position: Long) = events!!.state(playing, position, track?.durationMs ?: 0, false)

        fun end() = events!!.ended()
    }
}
