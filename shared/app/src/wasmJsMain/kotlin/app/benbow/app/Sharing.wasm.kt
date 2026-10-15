package app.benbow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import app.benbow.client.ByteSink
import app.benbow.client.byteSourceOf
import app.benbow.client.readBlob
import app.benbow.client.toUint8Array
import kotlinx.coroutines.launch

@Composable
internal actual fun rememberCopy(): (String) -> Boolean = { text ->
    copyText(text)
    false
}

/** No share sheet in the browser: the text goes to the clipboard. */
@Composable
internal actual fun rememberShare(): (String) -> Boolean = { text ->
    copyText(text)
    false
}

@Composable
internal actual fun rememberOpenFile(mimes: List<String>, onPicked: (PickedFile?) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()
    return {
        pickFiles(mimes.filter { it != "*/*" }.joinToString(","), multiple = false) { files ->
            val file = arrayItem(files, 0)
            scope.launch {
                val bytes = readBlob(file)
                callback(PickedFile(fileName(file)) { byteSourceOf(bytes) })
            }
        }
    }
}

/** Saving is a download: the browser puts the file in its downloads (or asks where, as set up). */
@Composable
internal actual fun rememberSaveFile(mime: String, onPicked: (open: (() -> ByteSink)?) -> Unit): (String) -> Unit = { name ->
    onPicked { DownloadSink(name, mime) }
}

/** Collects what is written; closing it hands the whole file to the browser as a download. */
private class DownloadSink(private val name: String, private val mime: String) : ByteSink() {
    private val parts = ArrayList<ByteArray>()

    override fun write(bytes: ByteArray) {
        parts += bytes.copyOf()
    }

    override fun close() {
        val all = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(all, at)
            at += part.size
        }
        saveDownload(name, mime, all.toUint8Array())
    }
}
