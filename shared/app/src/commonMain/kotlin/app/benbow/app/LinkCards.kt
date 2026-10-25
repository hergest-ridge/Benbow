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
import app.benbow.client.byteSourceOf
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.LinkCardBlock
import app.benbow.core.text.Atom
import app.benbow.core.text.Links
import app.benbow.core.text.Mark
import app.benbow.ui.editor.LinkHandler
import app.benbow.ui.editor.LinkPictureLook
import app.benbow.ui.editor.LocalLinkHandler
import app.benbow.ui.editor.LocalLinkLoading
import app.benbow.ui.editor.LocalLinkPicture
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Links in the app (ADR-038): where a tapped one opens, a card's picture, and reading a card's page once, as it goes in.
 */

/**
 * What a page said, ready for its card; [image] is a JPEG blob's SHA-256 that this device or the server keeps. An
 * address that is a picture gives the picture itself as [image], [width] by [height]: its card shows it whole.
 */
internal data class LinkPreview(
    val title: String?,
    val description: String?,
    val site: String?,
    val image: String?,
    val width: Int? = null,
    val height: Int? = null,
)

/** Reads [href]'s page for its card: the phone and the desktop directly, the browser through its server. Null: no page. */
internal expect suspend fun readPage(services: AppServices, href: String): LinkPreview?

/** Opens a web, mail or phone address outside the app: a browser sheet over the app on Android, the system browser on a PC. */
@Composable
internal expect fun rememberOpenUrl(): (String) -> Unit

/**
 * Keeps a card's picture as a file of its own, uploaded and backed up like an attachment's preview: a JPEG, or a picture
 * link's file as the site gave it (a GIF stays a GIF).
 */
internal suspend fun AppServices.keepPicture(bytes: ByteArray, mime: String = "image/jpeg"): String {
    val file = media.import(byteSourceOf(bytes), mime, "link." + pictureExtension(mime), now())
    repo.keepFile(file)
    return file.sha256
}

internal fun pictureExtension(mime: String): String = when (mime) {
    "image/gif" -> "gif"
    "image/png" -> "png"
    "image/webp" -> "webp"
    else -> "jpg"
}

/**
 * The card with what its page said; anything the card already has (a title written by hand) stays. A link that is a
 * picture shows it whole («Картинкой», owner: GIFs by their links didn't show), unless the card already has a picture.
 */
internal fun Atom.LinkCard.filled(preview: LinkPreview?, day: String): Atom.LinkCard {
    val own = preview?.takeIf { image == null && it.image != null && it.width != null && it.height != null }
    return copy(
        title = title ?: preview?.title,
        description = description ?: preview?.description,
        site = site ?: preview?.site,
        image = image ?: preview?.image,
        fetched = day,
        picture = picture || (own != null && !large),
        width = width ?: own?.width,
        height = height ?: own?.height,
    )
}

/**
 * Reads the pages of cards that haven't been read (once per run each), shimmering them meanwhile, and hands each result
 * to [fill]; nothing happens with «Превью ссылок» off. A page that couldn't be read is tried again another time.
 */
internal fun AppServices.readCards(cards: List<Pair<String, Atom.LinkCard>>, fill: suspend (key: String, LinkPreview?) -> Unit) {
    if (!appearance.value.linkPreviews) return
    for ((key, card) in cards) {
        if (card.fetched != null || !Links.isOpenable(card.href) || !triedPages.add(card.href)) continue
        linkLoading.update { it + card.href }
        scope.launch {
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
        }
    }
}

/** Cards of entry [id] read after the editor closed (or a shared link's note): written as a revision of their own. */
internal fun AppServices.readCardsOf(id: String) = scope.launch {
    val body = repo.entry(id).first()?.body ?: return@launch
    val doc = runCatching { DocumentCodec.decode(body) }.getOrNull()?.takeIf { it.isEditable } ?: return@launch
    val cards = doc.blocks.filterIsInstance<LinkCardBlock>().filter { it.fetched == null }.map { it.key to it.atom() }
    readCards(cards) { key, preview -> fillStoredCard(id, key, preview) }
}

/** Writes a read page into card [key] of the stored entry, unless an editor has the entry open (it reads it then). */
internal fun AppServices.fillStoredCard(id: String, key: String, preview: LinkPreview?) {
    if (openDrafts.any { it.entryId == id }) return
    repo.reviseBody(id) { body ->
        val doc = runCatching { DocumentCodec.decode(body) }.getOrNull()?.takeIf { it.isEditable } ?: return@reviseBody null
        val blocks = doc.blocks.map { block ->
            if (block !is LinkCardBlock || block.key != key || block.fetched != null) return@map block
            val card = block.atom().filled(preview, today())
            block.copy(
                title = card.title,
                description = card.description,
                site = card.site,
                image = card.image,
                fetched = card.fetched,
                view = if (card.picture) "picture" else block.view,
                width = card.width,
                height = card.height,
            )
        }
        DocumentCodec.encode(Document(blocks, doc.version))
    }
}

private fun LinkCardBlock.atom() = Atom.LinkCard(
    href,
    title,
    description,
    site,
    image,
    view == "large",
    fetched,
    picture = view == "picture",
    width = width,
    height = height,
)

/**
 * Links for every screen: addresses open outside the app, entry links open the entry; a card's picture comes from the
 * media store, and cards being read shimmer.
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
    val picture = remember(services) {
        @Composable { sha256: String, look: LinkPictureLook, modifier: Modifier -> LinkPicture(services, sha256, look, modifier) }
    }
    CompositionLocalProvider(
        LocalLinkHandler provides handler,
        LocalLinkPicture provides picture,
        LocalLinkLoading provides loading,
        content = content,
    )
}

/**
 * A card's picture; a small square one (a site's icon) sits in the middle of the big look instead of being stretched.
 * A link that is a picture shows it whole, and a GIF moves.
 */
@Composable
private fun LinkPicture(services: AppServices, sha256: String, look: LinkPictureLook, modifier: Modifier) {
    val load by rememberMediaFile(services.media, sha256)
    val file = (load as? MediaLoad.Ready)?.file ?: return
    if (look == LinkPictureLook.Whole) return MovingPicture(file, modifier, ContentScale.Fit)
    val large = look == LinkPictureLook.Large
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
