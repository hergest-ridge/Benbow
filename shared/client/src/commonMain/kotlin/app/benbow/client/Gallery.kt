package app.benbow.client

import app.benbow.core.content.AttachmentBlock
import app.benbow.core.content.DocumentCodec
import app.benbow.core.sync.AttachmentState

/**
 * An entry's attachments in gallery order (the ribbon, the viewer, the feed's carousel): those placed in the text first,
 * in the order they appear there, then the rest in the order the user gave them ([AttachmentState.position]).
 */
fun galleryOrder(attachments: Collection<AttachmentState>, body: String): List<AttachmentState> {
    val present = attachments.filter { it.present }
    val byId = present.associateBy { it.meta.id }
    val inText = placedIn(body).mapNotNull { byId[it] }.distinctBy { it.meta.id }
    val placed = inText.mapTo(HashSet()) { it.meta.id }
    return inText + present.filter { it.meta.id !in placed }.sortedBy { it.position }
}

/** Ids of the attachments placed in the text, in order of appearance. */
fun placedIn(body: String): List<String> =
    runCatching { DocumentCodec.decode(body).blocks.filterIsInstance<AttachmentBlock>().map { it.ref } }.getOrDefault(emptyList())
