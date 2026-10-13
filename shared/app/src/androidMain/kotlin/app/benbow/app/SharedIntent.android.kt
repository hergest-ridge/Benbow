package app.benbow.app

import android.content.Context
import android.content.Intent
import android.net.Uri

/** What another app shared with us (ACTION_SEND / ACTION_SEND_MULTIPLE): text, a link with its title, files. */
fun sharedContent(context: Context, intent: Intent): SharedContent {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return SharedContent.Empty
    val uris = buildList {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
        val clip = intent.clipData
        if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add)
    }.distinct()
    val text = sharedNoteText(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
    val resolver = context.contentResolver
    val files = uris.map { uri ->
        val mime = resolver.getType(uri) ?: intent.type?.takeIf { '*' !in it } ?: "application/octet-stream"
        SharedFile(mime, thumbnail = uri, open = {
            resolver.openInputStream(uri) ?: error("Can't read $uri")
        }, probe = { AndroidProbe.uri(context, uri) })
    }
    return SharedContent(text, files)
}
