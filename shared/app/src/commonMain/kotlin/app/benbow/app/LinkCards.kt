package app.benbow.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.client.MediaFile
import app.benbow.client.byteSourceOf
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.LinkCardBlock
import app.benbow.core.content.toDocument
import app.benbow.core.content.toRichText
import app.benbow.core.text.Atom
import app.benbow.core.text.BlockKeys
import app.benbow.core.text.Links
import app.benbow.core.text.Mark
import app.benbow.core.text.cardToPicture
import app.benbow.ui.editor.LinkHandler
import app.benbow.ui.editor.LocalLinkHandler
import app.benbow.ui.editor.LocalLinkLoading
import app.benbow.ui.editor.LocalLinkPicture
import app.benbow.ui.editor.LocalPicturesComing
import app.benbow.ui.editor.PictureLoading
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Links in the app (ADR-038): where a tapped one opens, a card's picture, and reading a card's page once, as it goes in.
 * A link that is a picture brings the picture as an attachment under it (ADR-045).
 */

/**
 * What a page said, ready for its card; [image] is a JPEG blob's SHA-256 that this device or the server keeps. An address
 * that is a picture (or a GIF site's page) brings [picture] instead: it goes under the link as an attachment (ADR-045).
 */
internal data class LinkPreview(
    val title: String?,
    val description: String?,
    val site: String?,
    val image: String?,
    val picture: LinkPicture? = null,
)

/** A link's own picture (ADR-045), as the site gave it, in this device's store: its size and a small JPEG of it for lists. */
internal class LinkPicture(val file: MediaFile, val width: Int, val height: Int, val preview: MediaFile?)

/** Reads [href]'s page for its card: the phone and the desktop directly, the browser through its server. Null: no page. */
internal expect suspend fun readPage(services: AppServices, href: String): LinkPreview?

/** Opens a web, mail or phone address outside the app: a browser sheet over the app on Android, the system browser on a PC. */
@Composable
internal expect fun rememberOpenUrl(): (String) -> Unit

/** Keeps a card's picture as a file of its own, uploaded and backed up like an attachment's preview. */
internal suspend fun AppServices.keepPicture(jpeg: ByteArray): String {
    val file = media.import(byteSourceOf(jpeg), "image/jpeg", "link.jpg", now())
    repo.keepFile(file)
    return file.sha256
}

/** A link's picture into this device's store as it came (a GIF stays a GIF), with a small JPEG of it, ready to attach. */
internal suspend fun AppServices.storeLinkPicture(href: String, bytes: ByteArray, mime: String, width: Int, height: Int): LinkPicture {
    val file = media.import(byteSourceOf(bytes), mime, pictureName(href, mime), null)
    val preview = coverJpeg(bytes, PICTURE_PREVIEW)?.let { media.import(byteSourceOf(it), "image/jpeg", null, null) }
    return LinkPicture(file, width, height, preview)
}

/** The picture's file name from its address when it has one ("giphy.gif"), else "link.gif". */
internal fun pictureName(href: String, mime: String): String {
    val last = href.substringBefore('?').substringBefore('#').substringAfterLast('/')
    return last.takeIf { '.' in it && it.length <= NAME_MAX } ?: ("link." + pictureExtension(mime))
}

private fun pictureExtension(mime: String): String = when (mime) {
    "image/gif" -> "gif"
    "image/png" -> "png"
    "image/webp" -> "webp"
    else -> "jpg"
}

private const val PICTURE_PREVIEW = 512
private const val NAME_MAX = 80

/**
 * «Обновить карточку»: what its page says now in place of what it said, when the page named the link at all (else the
 * card keeps its words); the description goes with the title, so a site that has none now (YouTube's ads) drops it.
 */
internal fun Atom.LinkCard.renewed(preview: LinkPreview?, day: String): Atom.LinkCard {
    if (preview?.title == null) return copy(fetched = day)
    return copy(
        title = preview.title,
        description = preview.description,
        site = preview.site ?: site,
        image = preview.image ?: image,
        fetched = day,
    )
}

/** The card with what its page said; anything the card already has (a title written by hand) stays. */
internal fun Atom.LinkCard.filled(preview: LinkPreview?, day: String): Atom.LinkCard = copy(
    title = title ?: preview?.title,
    description = description ?: preview?.description,
    site = site ?: preview?.site,
    image = image ?: preview?.image,
    fetched = day,
)

/**
 * Reads the pages of cards that haven't been read (once per run each), shimmering them meanwhile, and hands each result
 * to [fill]; nothing happens with «Превью ссылок» off. A page that couldn't be read is tried again another time. A card
 * whose address is surely a picture shows where the picture will be until [fill] has put it there (ADR-045).
 */
internal fun AppServices.readCards(cards: List<Pair<String, Atom.LinkCard>>, fill: suspend (key: String, LinkPreview?) -> Unit) {
    if (!appearance.value.linkPreviews) return
    for ((key, card) in cards) {
        if (card.fetched != null || !Links.isOpenable(card.href) || !triedPages.add(card.href)) continue
        linkLoading.update { it + card.href }
        if (Links.looksLikePicture(card.href)) pictureComing(card.href, null, null, card.href.substringBefore('?').endsWith(".gif"))
        scope.launch {
            try {
                val preview = try {
                    pageReader(card.href)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                linkLoading.update { it - card.href }
                if (preview == null) {
                    triedPages.remove(card.href)
                    return@launch
                }
                fill(key, preview)
            } finally {
                linkLoading.update { it - card.href }
                picturesComing.update { it - card.href }
            }
        }
    }
}

/** [href] is a picture on its way (ADR-045): its card shows a picture's place, at its size once that is known. */
internal fun AppServices.pictureComing(href: String, width: Int?, height: Int?, gif: Boolean) {
    picturesComing.update { it + (href to PictureLoading(width, height, gif || Links.isGifSite(href))) }
}

/** Cards of entry [id] read after the editor closed (or a shared link's note): written as a revision of their own. */
internal fun AppServices.readCardsOf(id: String) = scope.launch {
    val body = repo.entry(id).first()?.body ?: return@launch
    val doc = runCatching { DocumentCodec.decode(body) }.getOrNull()?.takeIf { it.isEditable } ?: return@launch
    val cards = doc.blocks.filterIsInstance<LinkCardBlock>().filter { it.fetched == null }
        .map { it.key to Atom.LinkCard(it.href, it.title, it.description, it.site, it.image, it.view == "large", it.fetched) }
    readCards(cards) { key, preview ->
        val picture = preview?.picture
        if (picture == null) {
            fillStoredCard(id, key, preview)
        } else if (openDrafts.none { it.entryId == id } && storedCard(id, key)) {
            placeStoredPicture(id, key, attachLinkPicture(id, picture), appearance.value.pictureLinks)
        }
    }
}

/** Writes a read page into card [key] of the stored entry, unless an editor has the entry open (it reads it then). */
internal fun AppServices.fillStoredCard(id: String, key: String, preview: LinkPreview?) {
    if (openDrafts.any { it.entryId == id }) return
    repo.reviseBody(id) { body ->
        val doc = runCatching { DocumentCodec.decode(body) }.getOrNull()?.takeIf { it.isEditable } ?: return@reviseBody null
        val blocks = doc.blocks.map { block ->
            if (block !is LinkCardBlock || block.key != key || block.fetched != null) return@map block
            val card = Atom.LinkCard(
                block.href,
                block.title,
                block.description,
                block.site,
                block.image,
                block.view == "large",
            ).filled(preview, today())
            block.copy(title = card.title, description = card.description, site = card.site, image = card.image, fetched = card.fetched)
        }
        DocumentCodec.encode(Document(blocks, doc.version))
    }
}

/** A link's picture attached to entry [id] (ADR-045); its id. The caller puts it in the text. */
internal fun AppServices.attachLinkPicture(id: String, picture: LinkPicture): String =
    repo.attach(id, picture.file, "image", picture.width, picture.height, preview = picture.preview)

/** Whether the stored entry [id] still has card [key] (the page came after the editor closed). */
internal suspend fun AppServices.storedCard(id: String, key: String): Boolean {
    val body = repo.entry(id).first()?.body ?: return false
    return runCatching { DocumentCodec.decode(body) }.getOrNull()?.blocks?.any { it is LinkCardBlock && it.key == key } == true
}

/** Card [key] of the stored entry becomes its link with picture [ref] under it, or the picture alone ([keepLink] off). */
internal fun AppServices.placeStoredPicture(id: String, key: String, ref: String, keepLink: Boolean) {
    repo.reviseBody(id) { body ->
        val doc = runCatching { DocumentCodec.decode(body) }.getOrNull()?.takeIf { it.isEditable } ?: return@reviseBody null
        val text = doc.toRichText()
        val at = text.paragraphs.indexOfFirst { it.key == key && it.atom is Atom.LinkCard }.takeIf { it >= 0 } ?: return@reviseBody null
        val placed = text.cardToPicture(at, ref, keepLink, BlockKeys()) ?: return@reviseBody null
        DocumentCodec.encode(placed.toDocument().copy(version = doc.version))
    }
}

/**
 * Links for every screen: addresses open outside the app, entry links open the entry; a card's picture comes from the
 * media store, and cards being read shimmer (or, when they are surely pictures, show where the picture will be).
 */
@Composable
internal fun ProvideLinks(services: AppServices, nav: Navigator, content: @Composable () -> Unit) {
    val platformOpen = rememberOpenUrl()
    val openUrl = services.openUrlOverride ?: platformOpen
    val copy = rememberCopy()
    val scope = rememberCoroutineScope()
    val handler = remember(openUrl, copy, nav, scope) {
        object : LinkHandler {
            override fun open(mark: Mark) {
                when (mark) {
                    is Mark.Link -> if (Links.isOpenable(mark.href)) openUrl(mark.href.trim())

                    is Mark.EntryLink -> scope.launch {
                        val entry = services.repo.entry(mark.entryId).first() ?: return@launch
                        nav.open(Route.Editor(entry.id, entry.day, readOnly = true))
                    }

                    else -> Unit
                }
            }

            override fun copy(href: String) {
                if (!copy(href)) services.messages.tryEmit(AppMessage.Copied)
            }
        }
    }
    val loading by services.linkLoading.collectAsStateWithLifecycle()
    val coming by services.picturesComing.collectAsStateWithLifecycle()
    val picture = remember(services) {
        @Composable { sha256: String, large: Boolean, modifier: Modifier -> LinkPicture(services, sha256, large, modifier) }
    }
    CompositionLocalProvider(
        LocalLinkHandler provides handler,
        LocalLinkPicture provides picture,
        LocalLinkLoading provides loading,
        LocalPicturesComing provides coming,
        content = content,
    )
}

/** A card's picture; a small square one (a site's icon) sits in the middle of the big look instead of being stretched. */
@Composable
private fun LinkPicture(services: AppServices, sha256: String, large: Boolean, modifier: Modifier) {
    val load by rememberMediaFile(services.media, sha256)
    val file = (load as? MediaLoad.Ready)?.file ?: return
    var icon by remember(sha256) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            file,
            null,
            if (large && icon) Modifier.size(72.dp) else Modifier.fillMaxSize(),
            contentScale = if (large && icon) ContentScale.Fit else ContentScale.Crop,
            onState = { state ->
                val size = (state as? AsyncImagePainter.State.Success)?.result?.image
                if (size != null) icon = size.width <= ICON_MAX && size.height <= ICON_MAX && size.width == size.height
            },
        )
    }
}

private const val ICON_MAX = 256
