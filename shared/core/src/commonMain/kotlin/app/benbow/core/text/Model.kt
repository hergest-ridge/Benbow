package app.benbow.core.text

import kotlin.random.Random

/** Inline formatting (decorators and annotations of docs/content-format.md). */
sealed interface Mark {
    /** Marks of the same group never overlap: applying one replaces the other in that range. */
    val group: String? get() = null

    /** Whether text typed right after the mark continues it. */
    val inclusive: Boolean get() = true

    data object Bold : Mark
    data object Italic : Mark
    data object Underline : Mark
    data object Strike : Mark
    data object Code : Mark
    data object Spoiler : Mark

    data class Color(val token: String) : Mark {
        override val group get() = "color"
    }

    data class Highlight(val token: String) : Mark {
        override val group get() = "highlight"
    }

    data class Size(val step: SizeStep) : Mark {
        override val group get() = "size"
    }

    data class Link(val href: String) : Mark {
        override val group get() = "link"
        override val inclusive get() = false
    }

    data class EntryLink(val entryId: String, val blockKey: String? = null) : Mark {
        override val group get() = "link"
        override val inclusive get() = false
    }
}

enum class SizeStep { Small, Large, XLarge }

/** [mark] applied to text offsets `[start, end)`. */
data class MarkSpan(val mark: Mark, val start: Int, val end: Int)

enum class BlockStyle { Normal, H1, H2, H3, Quote, Code }

enum class ListKind { Bullet, Number, Check }

enum class Align { Start, Center, End }

/** Paragraphs sharing a callout [key] render as one box. */
data class Callout(val key: String, val tone: String, val icon: String)

/** Non-text block occupying one paragraph whose text is exactly [ATOM_CHAR]. */
sealed interface Atom {
    data class Divider(val variant: String? = null) : Atom
    data class Attachment(val ref: String, val width: Double? = null, val caption: String? = null) : Atom

    /** Two or three attachments side by side, one height for all (ADR-061). */
    data class AttachmentRow(val refs: List<String>) : Atom

    /**
     * A link shown as a card (ADR-038) with what its page said when the link went in: [image] is the SHA-256 of a JPEG
     * blob, [large] the big look (else a row), [fetched] the day the page was read (null: not yet).
     */
    data class LinkCard(
        val href: String,
        val title: String? = null,
        val description: String? = null,
        val site: String? = null,
        val image: String? = null,
        val large: Boolean = false,
        val fetched: String? = null,
    ) : Atom

    /** A block this build doesn't understand, kept verbatim (canonical JSON of the block). */
    data class Unknown(val json: String) : Atom
}

val ATOM_CHAR: Char = Char(0xFFFC) // OBJECT REPLACEMENT CHARACTER

/** Attributes of one `\n`-separated paragraph. [key] is the stable block identity. */
data class Paragraph(
    val key: String,
    val style: BlockStyle = BlockStyle.Normal,
    val list: ListKind? = null,
    val level: Int = 1,
    val checked: Boolean = false,
    val align: Align = Align.Start,
    val callout: Callout? = null,
    val atom: Atom? = null,
    /** Code-block language; meaningful on the first paragraph of a code block. */
    val language: String? = null,
) {
    val isHeading: Boolean get() = style == BlockStyle.H1 || style == BlockStyle.H2 || style == BlockStyle.H3

    /** Attributes for a paragraph created by splitting this one. */
    internal fun continuation(newKey: String) =
        copy(key = newKey, style = if (isHeading) BlockStyle.Normal else style, checked = false, atom = null)

    /** Peels one layer of block formatting (used by Enter on an empty item and Backspace at start). */
    internal fun exitOneLevel(): Paragraph = when {
        list != null && level > 1 -> copy(level = level - 1)
        list != null -> copy(list = null, level = 1, checked = false)
        style != BlockStyle.Normal -> copy(style = BlockStyle.Normal)
        callout != null -> copy(callout = null)
        else -> this
    }
}

/** Generates 8-char base62 block keys. */
class BlockKeys(private val random: Random = Random.Default) {
    fun next(): String = buildString(KEY_LENGTH) { repeat(KEY_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    private companion object {
        const val KEY_LENGTH = 8
        const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    }
}
