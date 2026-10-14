package app.benbow.server

import app.benbow.core.api.SpeechModelFile
import app.benbow.core.api.SpeechModelSpec
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechModelTest {
    private val encoder = Random(1).nextBytes(50_000)
    private val tokens = "<blk> 0\nа 1\n".toByteArray()
    private val vad = Random(2).nextBytes(3_000)

    private fun sha(bytes: ByteArray) = sha256(bytes.inputStream())

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            for ((name, bytes) in entries) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private fun spec(archive: ByteArray) = SpeechModel.Spec(
        archive = SpeechModelSpec.Source("https://example.invalid/model.tar.bz2", archive.size.toLong(), sha(archive)),
        vad = SpeechModelSpec.Source("https://example.invalid/vad.onnx", vad.size.toLong(), sha(vad), file = "silero_vad.onnx"),
        files = listOf(
            SpeechModelFile("encoder.int8.onnx", encoder.size.toLong(), sha(encoder)),
            SpeechModelFile("tokens.txt", tokens.size.toLong(), sha(tokens)),
            SpeechModelFile("silero_vad.onnx", vad.size.toLong(), sha(vad)),
        ),
    )

    /** Serves made-up bytes by URL instead of the internet; records what was asked for. */
    private class FakeFetch(private val served: Map<String, ByteArray>) : SpeechModel.Fetch {
        val asked = ArrayList<String>()

        override fun download(url: String, size: Long, target: Path, progress: (Long) -> Unit) {
            asked += url
            val bytes = served.getValue(url)
            target.writeBytes(bytes)
            progress(bytes.size.toLong())
        }
    }

    @Test
    fun downloadsChecksAndUnpacksOnlyThePinnedFiles() {
        val root = Files.createTempDirectory("benbow-speech")
        val archive = archive(
            "model/encoder.int8.onnx" to encoder,
            "model/tokens.txt" to tokens,
            "model/run.sh" to "rm -rf /".toByteArray(), // not asked for: never unpacked
        )
        val fetch = FakeFetch(mapOf("https://example.invalid/model.tar.bz2" to archive, "https://example.invalid/vad.onnx" to vad))
        val model = SpeechModel(root, fetch, spec(archive))
        assertEquals(SpeechModel.State.Missing, model.state)
        assertNull(model.file("encoder.int8.onnx"), "nothing to serve before the download")

        model.runDownload()

        assertEquals(SpeechModel.State.Ready, model.state)
        assertContentEquals(encoder, model.file("encoder.int8.onnx")!!.readBytes())
        assertContentEquals(vad, model.file("silero_vad.onnx")!!.readBytes())
        assertNull(model.file("run.sh"), "only pinned names are served")
        assertFalse(root.resolve(SpeechModelSpec.ID).resolve("run.sh").exists())
        assertEquals(2, fetch.asked.size, "the archive and the VAD, nothing else")
        assertEquals(SpeechModel.State.Ready, SpeechModel(root, fetch, spec(archive)).state, "found again after a restart")
    }

    @Test
    fun aFileThatDoesNotMatchItsHashIsNeverUsed() {
        val root = Files.createTempDirectory("benbow-speech")
        val good = archive("model/encoder.int8.onnx" to encoder, "model/tokens.txt" to tokens)
        val forged = archive("model/encoder.int8.onnx" to Random(9).nextBytes(50_000), "model/tokens.txt" to tokens)
        val fetch = FakeFetch(mapOf("https://example.invalid/model.tar.bz2" to forged, "https://example.invalid/vad.onnx" to vad))
        val model = SpeechModel(root, fetch, spec(good))

        model.runDownload()

        assertIs<SpeechModel.State.Failed>(model.state)
        assertNull(model.file("encoder.int8.onnx"))
        assertFalse(root.resolve(SpeechModelSpec.ID).resolve("encoder.int8.onnx").exists())
        assertTrue(Files.list(root.resolve(".download")).use { it.count() } == 0L, "no leftovers")
    }
}
