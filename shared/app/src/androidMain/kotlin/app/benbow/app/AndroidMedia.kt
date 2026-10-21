package app.benbow.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Reads what a picked or captured file is: type, size, date, duration, and a small JPEG preview. */
internal object AndroidProbe {
    private const val PREVIEW = 512

    fun uri(context: Context, uri: Uri): MediaProbe {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        val mime = refineMime(resolver.getType(uri), name)
        return when (kindOf(mime)) {
            "image" -> image(mime, name) { resolver.openInputStream(uri)!! }
            "video" -> video(mime, name) { it.setDataSource(context, uri) }
            "audio" -> MediaProbe(mime, name, "audio", durationMs = audioDuration { it.setDataSource(context, uri) })
            else -> MediaProbe(mime, name, kindOf(mime))
        }
    }

    fun photo(file: File): MediaProbe = image("image/jpeg", file.name) { file.inputStream() }

    private fun image(mime: String, name: String?, open: () -> InputStream): MediaProbe {
        val exif = runCatching { open().use { ExifInterface(it) } }.getOrNull()
        val rotation = exif?.rotationDegrees ?: 0
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        val (w, h) = if (rotation % 180 == 0) bounds.outWidth to bounds.outHeight else bounds.outHeight to bounds.outWidth
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW) sample *= 2
        val bitmap = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        return MediaProbe(
            mime = mime,
            name = name,
            kind = "image",
            width = w.takeIf { it > 0 },
            height = h.takeIf { it > 0 },
            capturedAt = exif?.dateTimeOriginal ?: exif?.dateTime,
            preview = bitmap?.let { jpeg(it, rotation) },
        )
    }

    private fun video(mime: String, name: String?, source: (MediaMetadataRetriever) -> Unit): MediaProbe {
        val retriever = MediaMetadataRetriever()
        return try {
            source(retriever)
            fun meta(key: Int) = retriever.extractMetadata(key)
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val frame = retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PREVIEW, PREVIEW)
            MediaProbe(
                mime = mime,
                name = name,
                kind = "video",
                width = if (rotation % 180 == 0) w else h,
                height = if (rotation % 180 == 0) h else w,
                durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                capturedAt = meta(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(::parseVideoDate),
                preview = frame?.let { jpeg(it, 0) }, // frames come out upright
            )
        } catch (_: RuntimeException) {
            MediaProbe(mime, name, "video")
        } finally {
            retriever.release()
        }
    }

    /** A sound's length as the platform's decoder sees it; its tags are read by our own reader (ADR-035). */
    private fun audioDuration(source: (MediaMetadataRetriever) -> Unit): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            source(retriever)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    /** An embedded cover, decoded at about [max] px and saved as a JPEG. */
    fun cover(image: ByteArray, max: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size, options) ?: return null
        val scale = minOf(1f, max.toFloat() / maxOf(bitmap.width, bitmap.height))
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }

    private fun jpeg(bitmap: Bitmap, rotation: Int): ByteArray {
        val scale = minOf(1f, PREVIEW.toFloat() / maxOf(bitmap.width, bitmap.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val small = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    /** "20261013T014512.000Z" */
    private fun parseVideoDate(text: String): Long? = runCatching {
        SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(text)?.time
    }.getOrNull()?.takeIf { it > 0 }
}

/** Voice notes: AAC in .m4a, recorded into app storage while the «голос» key is down. */
internal class VoiceRecorder(private val context: Context) {
    var recording by mutableStateOf(false)
        private set
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start(): Boolean {
        val target = File(File(context.filesDir, "capture").apply { mkdirs() }, "voice-${System.currentTimeMillis()}.m4a")
        return try {
            recorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setAudioChannels(1)
                setOutputFile(target)
                prepare()
                start()
            }
            file = target
            startedAt = SystemClock.elapsedRealtime()
            recording = true
            VoiceRecording.active.value = true
            true
        } catch (_: Exception) {
            recorder?.release()
            recorder = null
            target.delete()
            false
        }
    }

    /** The finished recording and its length, or null when it failed. */
    fun stop(): Pair<File, Long>? {
        val active = recorder ?: return null
        recorder = null
        recording = false
        VoiceRecording.active.value = false
        val duration = SystemClock.elapsedRealtime() - startedAt
        return try {
            active.stop()
            file?.let { it to duration }
        } catch (_: RuntimeException) {
            file?.delete() // stopped right after starting: no audio was written
            null
        } finally {
            active.release()
        }
    }

    fun cancel() {
        stop()?.first?.delete()
    }

    /** How long the current recording runs, for the timer. */
    fun elapsedMs(): Long = if (recording) SystemClock.elapsedRealtime() - startedAt else 0

    /** How loud it was since the last call, 0..1, for the lamp (a peak taken as a sine's RMS). */
    fun level(): Float = levelOf((recorder?.maxAmplitude ?: 0) / 32768f / 1.414f)
}
