package app.benbow.core.content

import kotlinx.serialization.json.JsonObject

/** Benbow Document (docs/content-format.md): canonical storage of entry bodies. */
data class Document(val blocks: List<Block>, val version: Int = CURRENT_VERSION) {
    /** Documents from a newer format are shown but never re-saved (data-safety S13). */
    val isEditable: Boolean get() = version <= CURRENT_VERSION

    companion object {
        const val CURRENT_VERSION = 1
    }
}

sealed interface Block {
    val key: String
}

data class TextBlock(
    override val key: String,
    val style: String = STYLE_NORMAL,
    val listItem: String? = null,
    val level: Int? = null,
    val checked: Boolean? = null,
    val align: String? = null,
    val callout: CalloutRef? = null,
    val children: List<Span> = listOf(Span("")),
    val markDefs: List<MarkDef> = emptyList(),
) : Block

data class CalloutRef(val key: String, val tone: String, val icon: String)

data class Span(val text: String, val marks: List<String> = emptyList())

sealed interface MarkDef {
    val key: String

    data class Link(override val key: String, val href: String) : MarkDef
    data class EntryLink(override val key: String, val entryId: String, val blockKey: String? = null) : MarkDef
    data class Color(override val key: String, val token: String) : MarkDef
    data class Highlight(override val key: String, val token: String) : MarkDef
    data class Size(override val key: String, val step: String) : MarkDef
}

data class CodeBlock(override val key: String, val code: String, val language: String? = null, val callout: CalloutRef? = null) : Block

data class DividerBlock(override val key: String, val variant: String? = null) : Block

data class AttachmentBlock(override val key: String, val ref: String, val width: Double? = null, val caption: String? = null) : Block

/** A link as a card (ADR-038): what its page said when the link went in; [image] is a JPEG blob's SHA-256. */
data class LinkCardBlock(
    override val key: String,
    val href: String,
    val title: String? = null,
    val description: String? = null,
    val site: String? = null,
    val image: String? = null,
    val view: String? = null,
    val fetched: String? = null,
) : Block

/** Anything this build doesn't understand, kept verbatim (unknown-content rule). */
data class UnknownBlock(override val key: String, val raw: JsonObject) : Block

const val STYLE_NORMAL = "normal"

/** U+2028 LINE SEPARATOR. Written as a code point: invisible characters must never be literal in sources. */
val SOFT_BREAK: Char = Char(0x2028)

internal val TEXT_STYLES = setOf(STYLE_NORMAL, "h1", "h2", "h3", "blockquote")
internal val LIST_ITEMS = setOf("bullet", "number", "check")
internal val ALIGNS = setOf("start", "center", "end")
internal val DECORATORS = setOf("strong", "em", "underline", "strike-through", "code", "spoiler")
internal val SIZE_STEPS = setOf("small", "large", "xlarge")
internal val DIVIDER_VARIANTS = setOf("line", "dots", "wave")
internal val CARD_VIEWS = setOf("large")
internal val SHA256 = Regex("[0-9a-f]{64}")
internal val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

class DocumentFormatException(message: String) : IllegalArgumentException(message)
