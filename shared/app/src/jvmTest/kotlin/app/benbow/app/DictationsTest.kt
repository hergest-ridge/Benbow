package app.benbow.app

import app.benbow.client.SpeechModel
import app.benbow.client.SpeechModelStore
import app.benbow.client.byteSourceOf
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.benbow.core.api.SpeechModelFile
import app.benbow.core.api.SpeechModelSpec
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The dictation service (7.10) with a made-up microphone and recognizer: what happens to a take in every case. */
class DictationsTest {
    private val mic = FakeMic()
    private var hear: suspend (FloatArray) -> String = { "Фраза." }
    private val messages = java.util.concurrent.CopyOnWriteArrayList<AppMessage>()

    @Test
    fun aGoodTakeBecomesTextAndItsAudioWaitsForAttach() = runBlocking {
        val services = services()
        val entry = services.repo.createNote(services.today(), "")
        val target = ListTarget(entry)
        val take = mic.next(speech(1.5) + silence(1.0) + speech(1.0))
        assertTrue(services.dictations!!.start(target))
        services.dictations!!.stop(keep = true)
        eventually { messages.any { it is AppMessage.Dictated } }
        assertTrue(take.finishedCalled)
        assertEquals(listOf("Фраза.", "Фраза."), target.phrases)
        assertEquals(entry, take.entryId)
        val dictated = messages.filterIsInstance<AppMessage.Dictated>().single()
        assertEquals(entry, dictated.entryId)
        assertEquals(0, voices(services, entry), "no voice note unless asked")
        services.dictations!!.settle(dictated, attach = true)
        eventually { take.deleted }
        assertEquals(1, voices(services, entry))
    }

    @Test
    fun aTakeLetGoWithoutAttachIsDeleted() = runBlocking {
        val services = services()
        val entry = services.repo.createNote(services.today(), "")
        val take = mic.next(speech(1.5))
        services.dictations!!.start(ListTarget(entry))
        services.dictations!!.stop(keep = true)
        eventually { messages.any { it is AppMessage.Dictated } }
        services.dictations!!.settle(messages.filterIsInstance<AppMessage.Dictated>().single(), attach = false)
        eventually { take.deleted }
        assertEquals(0, voices(services, entry))
    }

    @Test
    fun aFailedTakeIsKeptAsAVoiceNoteOfItsEntry() = runBlocking {
        hear = { error("the recognizer stopped") }
        val services = services()
        val entry = services.repo.createNote(services.today(), "")
        val take = mic.next(speech(1.5) + silence(1.0))
        services.dictations!!.start(ListTarget(entry))
        eventually { services.dictations!!.failing.value }
        services.dictations!!.stop(keep = true)
        eventually { take.deleted }
        assertEquals(1, voices(services, entry))
        eventually { AppMessage.DictationKept in messages }
    }

    @Test
    fun aTakeWhoseScreenWentIsKept() = runBlocking {
        val services = services()
        val entry = services.repo.createNote(services.today(), "")
        val take = mic.next(speech(1.5))
        services.dictations!!.start(ListTarget(entry, open = false))
        services.dictations!!.stop(keep = true)
        eventually { take.deleted }
        assertEquals(1, voices(services, entry))
    }

    @Test
    fun aQuickNoteThatFailsMakesANoteOfItsOwn() = runBlocking {
        hear = { "" }
        val services = services()
        val take = mic.next(speech(1.5))
        services.dictations!!.start(ListTarget(entry = null))
        services.dictations!!.stop(keep = true)
        eventually { take.deleted }
        assertEquals(1, voices(services))
    }

    @Test
    fun aTakeThrownAwayLeavesNothing() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        hear = {
            gate.await()
            "Не нужно."
        }
        val services = services()
        val entry = services.repo.createNote(services.today(), "")
        val target = ListTarget(entry)
        val take = mic.next(speech(1.5) + silence(1.0) + speech(1.0))
        services.dictations!!.start(target)
        services.dictations!!.stop(keep = false)
        gate.complete(Unit)
        eventually { take.deleted }
        assertEquals(emptyList(), target.phrases)
        assertEquals(0, voices(services, entry))
        assertFalse(messages.any { it is AppMessage.Dictated })
    }

    @Test
    fun takesLeftByACrashBecomeVoiceNotesOnStart() = runBlocking {
        val first = FakeTake(FloatArray(0)).apply { belongsTo("gone-entry") }
        mic.left += first
        val services = services()
        eventually { first.deleted }
        assertEquals(1, voices(services), "the entry is gone, so a new note keeps it")
    }

    @Test
    fun withoutTheModelTheTextKeyDoesNothing() = runBlocking {
        val services = services(ready = false)
        mic.next(speech(1.0))
        assertFalse(services.dictations!!.start(ListTarget(null)))
        assertEquals(1, mic.takes.size, "the microphone wasn't opened")
    }

    /** Voice notes on [entry], or on every entry. */
    private suspend fun voices(services: AppServices, entry: String? = null): Int {
        val ids = entry?.let(::listOf) ?: services.repo.journal().first().map { it.id }
        return ids.sumOf { id -> services.repo.entry(id).first()?.attachments.orEmpty().count { it.meta.kind == "voice" } }
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(20)
    }

    private fun services(ready: Boolean = true): AppServices {
        val models = Files.createTempDirectory("benbow-models").toFile()
        val bytes = "model".toByteArray()
        val files = listOf(SpeechModelFile("encoder.int8.onnx", bytes.size.toLong(), "unused"))
        if (ready) {
            File(models, SpeechModelSpec.ID).apply {
                mkdirs()
                File(this, "encoder.int8.onnx").writeBytes(bytes)
                File(this, "verified").writeText(SpeechModelSpec.ID)
            }
        }
        val transcriber = object : Transcriber {
            override suspend fun <T> session(model: SpeechModel, block: suspend (Recognizer) -> T): T = block(
                object : Recognizer {
                    override suspend fun file(sound: File) = "не для диктовки"

                    override suspend fun samples(samples: FloatArray) = hear(samples)
                },
            )
        }
        return AppServices(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
            mediaIn(Files.createTempDirectory("benbow-media").toFile()),
            "android",
            "Test phone",
            transcriber = transcriber,
            speechModel = { api -> SpeechModelStore(File(models, SpeechModelSpec.ID), api, files) },
            mic = mic,
        ).also { services ->
            CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                services.messages.collect { messages += it }
            }
        }
    }

    private class ListTarget(private val entry: String?, private val open: Boolean = true) : DictationTarget {
        val phrases = ArrayList<String>()

        override suspend fun insert(phrase: String): Boolean {
            if (open) phrases += phrase
            return open
        }

        override suspend fun begin() = entry
    }

    private class FakeMic : DictationMic {
        val takes = ArrayDeque<FakeTake>()
        val left = ArrayList<TakeFile>()

        fun next(audio: FloatArray) = FakeTake(audio).also { takes += it }

        override fun start(): MicTake? = takes.removeFirstOrNull()

        override fun leftovers(): List<TakeFile> = left
    }

    /** Hands its audio over 100 ms at a time, then waits for [stop] like a microphone. */
    private class FakeTake(private val samples: FloatArray) : MicTake {
        @Volatile var deleted = false

        @Volatile var finishedCalled = false
        private var entry: String? = null
        private val stopped = CompletableDeferred<Unit>()

        override val audio: Flow<FloatArray> = flow {
            for (at in samples.indices step RATE / 10) emit(samples.copyOfRange(at, minOf(samples.size, at + RATE / 10)))
            stopped.await()
        }
        override val level = 0f
        override val elapsedMs = 0L
        override val entryId: String? get() = entry
        override val capturedAt = 1_000L

        override fun stop() {
            stopped.complete(Unit)
        }

        override fun belongsTo(entryId: String) {
            entry = entryId
        }

        override fun finished() {
            finishedCalled = true
        }

        override suspend fun voiceNote() = VoiceNoteFile({
            byteSourceOf(ByteArray(64) { it.toByte() })
        }, "voice-1.m4a", 1_500, capturedAt) {}

        override fun delete() {
            deleted = true
        }
    }

    private fun speech(seconds: Double) = FloatArray((seconds * RATE).toInt()) {
        val t = it.toDouble() / RATE
        (0.2 * (0.6 + 0.4 * sin(2 * PI * 4 * t)) * sin(2 * PI * 220 * t)).toFloat()
    }

    private fun silence(seconds: Double) = FloatArray((seconds * RATE).toInt())

    private companion object {
        const val RATE = 16_000
    }
}
