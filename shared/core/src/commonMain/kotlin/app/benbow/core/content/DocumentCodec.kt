package app.benbow.core.content

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToLong

/** Reads and writes the canonical serialization of docs/content-format.md. */
object DocumentCodec {

    fun decode(text: String): Document {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw DocumentFormatException("Not JSON: ${e.message}")
        }
        val obj = root as? JsonObject ?: throw DocumentFormatException("Root must be an object")
        val version = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw DocumentFormatException("Missing or invalid v")
        val blocks = obj["blocks"] as? JsonArray ?: throw DocumentFormatException("Missing blocks")
        val unknownEnvelope = obj.keys.any { it != "v" && it != "blocks" }
        return Document(blocks.map(::decodeBlock), if (unknownEnvelope) maxOf(version, Document.CURRENT_VERSION + 1) else version)
    }

    /** Canonical bytes: fixed key order, no insignificant whitespace, one block per line. */
    fun encode(document: Document): String {
        require(document.isEditable) { "A document from a newer format must not be re-encoded" }
        val doc = canonicalize(document)
        if (doc.blocks.isEmpty()) return "{\"v\":${doc.version},\"blocks\":[]}"
        return buildString {
            append("{\"v\":").append(doc.version).append(",\"blocks\":[\n")
            doc.blocks.forEachIndexed { i, block ->
                if (i > 0) append(",\n")
                appendBlock(block)
            }
            append("\n]}")
        }
    }

    /** Idempotent normalization (content-format.md#canonical-serialization). */
    fun canonicalize(document: Document): Document = document.copy(blocks = document.blocks.map { it.canonical() })

    private fun Block.canonical(): Block = when (this) {
        is TextBlock -> canonical()

        is AttachmentBlock -> copy(width = width?.let(::quantize))

        is LinkCardBlock -> copy(
            title = title?.takeIf { it.isNotBlank() },
            description = description?.takeIf { it.isNotBlank() },
            site = site?.takeIf { it.isNotBlank() },
            view = view?.takeIf { it != "row" },
        )

        is CodeBlock, is DividerBlock, is UnknownBlock -> this
    }

    private fun TextBlock.canonical(): TextBlock {
        val defsByKey = markDefs.associateBy { it.key }
        class Run(val text: String, val decorators: List<String>, val annotations: Set<MarkDef>)

        val runs = ArrayList<Run>()
        for (span in children) {
            if (span.text.isEmpty()) continue
            val decorators = span.marks.filter { it in DECORATORS }.distinct().sorted()
            val annotations = span.marks.mapNotNull { defsByKey[it]?.withKey("") }.toSet()
            val previous = runs.lastOrNull()
            if (previous != null && previous.decorators == decorators && previous.annotations == annotations) {
                runs[runs.size - 1] = Run(previous.text + span.text, decorators, annotations)
            } else {
                runs.add(Run(span.text, decorators, annotations))
            }
        }
        val keys = LinkedHashMap<MarkDef, String>()
        val spans = runs.map { run ->
            val annotationKeys = run.annotations.sortedBy { it.sortKey() }.map { def -> keys.getOrPut(def) { "m${keys.size + 1}" } }
            Span(run.text, run.decorators + annotationKeys.sortedBy { it.removePrefix("m").toInt() })
        }
        return copy(
            level = if (listItem != null) level ?: 1 else null,
            checked = if (listItem == "check") checked ?: false else null,
            align = align?.takeIf { it != "start" },
            children = spans.ifEmpty { listOf(Span("")) },
            markDefs = keys.map { (def, key) -> def.withKey(key) },
        )
    }

    private fun MarkDef.withKey(key: String): MarkDef = when (this) {
        is MarkDef.Link -> copy(key = key)
        is MarkDef.EntryLink -> copy(key = key)
        is MarkDef.Color -> copy(key = key)
        is MarkDef.Highlight -> copy(key = key)
        is MarkDef.Size -> copy(key = key)
    }

    private fun MarkDef.sortKey(): String = when (this) {
        is MarkDef.Link -> "link|$href"
        is MarkDef.EntryLink -> "entryLink|$entryId|${blockKey.orEmpty()}"
        is MarkDef.Color -> "color|$token"
        is MarkDef.Highlight -> "highlight|$token"
        is MarkDef.Size -> "size|$step"
    }

    /** Widths keep 4 decimals so every platform prints the same bytes. */
    private fun quantize(value: Double): Double = (value * 10_000).roundToLong() / 10_000.0

    private fun formatDecimal(value: Double): String {
        val scaled = (value * 10_000).roundToLong()
        val whole = scaled / 10_000
        val fraction = (scaled % 10_000).toString().padStart(4, '0').trimEnd('0')
        return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
    }

    // ---- encoding ----

    private fun q(value: String): String = JsonPrimitive(value).toString()

    private fun StringBuilder.field(name: String, rawValue: String) {
        if (last() != '{') append(',')
        append(q(name)).append(':').append(rawValue)
    }

    private fun StringBuilder.appendBlock(block: Block) {
        if (block is UnknownBlock) {
            append(block.raw.toString())
            return
        }
        append('{')
        field("_key", q(block.key))
        when (block) {
            is TextBlock -> {
                field("_type", q("block"))
                field("style", q(block.style))
                block.listItem?.let { field("listItem", q(it)) }
                block.level?.let { field("level", it.toString()) }
                block.checked?.let { field("checked", it.toString()) }
                block.align?.let { field("align", q(it)) }
                block.callout?.let { field("callout", calloutJson(it)) }
                field(
                    "children",
                    block.children.joinToString(",", "[", "]") { span ->
                        "{\"_type\":\"span\",\"text\":${q(span.text)},\"marks\":${span.marks.joinToString(",", "[", "]", transform = ::q)}}"
                    },
                )
                field("markDefs", block.markDefs.joinToString(",", "[", "]", transform = ::markDefJson))
            }

            is CodeBlock -> {
                field("_type", q("code"))
                block.language?.let { field("language", q(it)) }
                field("code", q(block.code))
                block.callout?.let { field("callout", calloutJson(it)) }
            }

            is DividerBlock -> {
                field("_type", q("divider"))
                block.variant?.let { field("variant", q(it)) }
            }

            is AttachmentBlock -> {
                field("_type", q("attachment"))
                field("ref", q(block.ref))
                block.width?.let { field("width", formatDecimal(it)) }
                block.caption?.let { field("caption", q(it)) }
            }

            is LinkCardBlock -> {
                field("_type", q("linkCard"))
                field("href", q(block.href))
                block.title?.let { field("title", q(it)) }
                block.description?.let { field("description", q(it)) }
                block.site?.let { field("site", q(it)) }
                block.image?.let { field("image", q(it)) }
                block.width?.let { field("width", it.toString()) }
                block.height?.let { field("height", it.toString()) }
                block.view?.let { field("view", q(it)) }
                block.fetched?.let { field("fetched", q(it)) }
            }

            is UnknownBlock -> error("handled above")
        }
        append('}')
    }

    private fun calloutJson(c: CalloutRef) = "{\"_key\":${q(c.key)},\"tone\":${q(c.tone)},\"icon\":${q(c.icon)}}"

    private fun markDefJson(def: MarkDef): String = buildString {
        append('{')
        field("_key", q(def.key))
        when (def) {
            is MarkDef.Link -> {
                field("_type", q("link"))
                field("href", q(def.href))
            }

            is MarkDef.EntryLink -> {
                field("_type", q("entryLink"))
                field("entryId", q(def.entryId))
                def.blockKey?.let { field("blockKey", q(it)) }
            }

            is MarkDef.Color -> {
                field("_type", q("color"))
                field("token", q(def.token))
            }

            is MarkDef.Highlight -> {
                field("_type", q("highlight"))
                field("token", q(def.token))
            }

            is MarkDef.Size -> {
                field("_type", q("size"))
                field("step", q(def.step))
            }
        }
        append('}')
    }

    // ---- decoding ----

    /** Thrown inside a block when anything is unknown or invalid; the block is kept verbatim then. */
    private class Unrecognized : Exception()

    private fun unrecognized(): Nothing = throw Unrecognized()

    private fun decodeBlock(element: JsonElement): Block {
        val obj = element as? JsonObject ?: throw DocumentFormatException("Block must be an object")
        val key = (obj["_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw DocumentFormatException("Block without _key")
        if ((obj["_type"] as? JsonPrimitive)?.isString != true) throw DocumentFormatException("Block $key without _type")
        return try {
            when (obj.getValue("_type").str()) {
                "block" -> decodeText(key, obj)
                "code" -> decodeCode(key, obj)
                "divider" -> decodeDivider(key, obj)
                "attachment" -> decodeAttachment(key, obj)
                "linkCard" -> decodeLinkCard(key, obj)
                else -> UnknownBlock(key, obj)
            }
        } catch (_: Unrecognized) {
            UnknownBlock(key, obj)
        }
    }

    private fun decodeText(key: String, obj: JsonObject): TextBlock {
        obj.allow("_key", "_type", "style", "listItem", "level", "checked", "align", "callout", "children", "markDefs")
        val style = obj["style"]?.str() ?: STYLE_NORMAL
        if (style !in TEXT_STYLES) unrecognized()
        val listItem = obj["listItem"]?.str()?.also { if (it !in LIST_ITEMS) unrecognized() }
        val level = obj["level"]?.int()?.also { if (listItem == null || it !in 1..6) unrecognized() }
        val checked = obj["checked"]?.bool()?.also { if (listItem != "check") unrecognized() }
        val align = obj["align"]?.str()?.also { if (it !in ALIGNS) unrecognized() }
        val callout = obj["callout"]?.let { decodeCallout(it.obj()) }
        val markDefs = (obj["markDefs"]?.arr() ?: JsonArray(emptyList())).map { decodeMarkDef(it.obj()) }
        val defKeys = markDefs.map { it.key }.toSet()
        if (defKeys.size != markDefs.size) unrecognized()
        val children = (obj["children"] ?: unrecognized()).arr().map { decodeSpan(it.obj(), defKeys) }
        return TextBlock(key, style, listItem, level, checked, align, callout, children.ifEmpty { listOf(Span("")) }, markDefs)
    }

    private fun decodeSpan(obj: JsonObject, defKeys: Set<String>): Span {
        obj.allow("_type", "text", "marks")
        if (obj["_type"]?.str() != "span") unrecognized()
        val text = (obj["text"] ?: unrecognized()).str().replace('\n', SOFT_BREAK)
        val marks = (obj["marks"]?.arr() ?: JsonArray(emptyList())).map { it.str() }
        if (marks.any { it !in DECORATORS && it !in defKeys }) unrecognized()
        return Span(text, marks)
    }

    private fun decodeMarkDef(obj: JsonObject): MarkDef {
        val key = (obj["_key"] ?: unrecognized()).str()
        return when ((obj["_type"] ?: unrecognized()).str()) {
            "link" -> {
                obj.allow("_key", "_type", "href")
                MarkDef.Link(key, (obj["href"] ?: unrecognized()).str())
            }

            "entryLink" -> {
                obj.allow("_key", "_type", "entryId", "blockKey")
                MarkDef.EntryLink(key, (obj["entryId"] ?: unrecognized()).str(), obj["blockKey"]?.str())
            }

            "color" -> {
                obj.allow("_key", "_type", "token")
                MarkDef.Color(key, (obj["token"] ?: unrecognized()).str())
            }

            "highlight" -> {
                obj.allow("_key", "_type", "token")
                MarkDef.Highlight(key, (obj["token"] ?: unrecognized()).str())
            }

            "size" -> {
                obj.allow("_key", "_type", "step")
                MarkDef.Size(key, (obj["step"] ?: unrecognized()).str().also { if (it !in SIZE_STEPS) unrecognized() })
            }

            else -> unrecognized()
        }
    }

    private fun decodeCallout(obj: JsonObject): CalloutRef {
        obj.allow("_key", "tone", "icon")
        return CalloutRef(
            (obj["_key"] ?: unrecognized()).str(),
            (obj["tone"] ?: unrecognized()).str(),
            (obj["icon"] ?: unrecognized()).str(),
        )
    }

    private fun decodeCode(key: String, obj: JsonObject): CodeBlock {
        obj.allow("_key", "_type", "language", "code", "callout")
        return CodeBlock(
            key,
            (obj["code"] ?: unrecognized()).str(),
            obj["language"]?.str(),
            obj["callout"]?.let {
                decodeCallout(it.obj())
            },
        )
    }

    private fun decodeDivider(key: String, obj: JsonObject): DividerBlock {
        obj.allow("_key", "_type", "variant")
        return DividerBlock(key, obj["variant"]?.str()?.also { if (it !in DIVIDER_VARIANTS) unrecognized() })
    }

    private fun decodeAttachment(key: String, obj: JsonObject): AttachmentBlock {
        obj.allow("_key", "_type", "ref", "width", "caption")
        val width = obj["width"]?.double()?.also { if (it !in 0.0..1.0) unrecognized() }
        return AttachmentBlock(key, (obj["ref"] ?: unrecognized()).str(), width, obj["caption"]?.str())
    }

    private fun decodeLinkCard(key: String, obj: JsonObject): LinkCardBlock {
        obj.allow("_key", "_type", "href", "title", "description", "site", "image", "width", "height", "view", "fetched")
        val href = (obj["href"] ?: unrecognized()).str().also { if (it.isBlank()) unrecognized() }
        return LinkCardBlock(
            key,
            href,
            title = obj["title"]?.str(),
            description = obj["description"]?.str(),
            site = obj["site"]?.str(),
            image = obj["image"]?.str()?.also { if (!SHA256.matches(it)) unrecognized() },
            view = obj["view"]?.str()?.also { if (it != "row" && it !in CARD_VIEWS) unrecognized() },
            fetched = obj["fetched"]?.str()?.also { if (!DAY.matches(it)) unrecognized() },
            width = obj["width"]?.int()?.also { if (it <= 0) unrecognized() },
            height = obj["height"]?.int()?.also { if (it <= 0) unrecognized() },
        )
    }

    private fun JsonObject.allow(vararg names: String) {
        if (keys.any { it !in names }) unrecognized()
    }

    private fun JsonElement.str(): String = ((this as? JsonPrimitive)?.takeIf { it.isString } ?: unrecognized()).content

    private fun JsonElement.int(): Int = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: unrecognized()

    private fun JsonElement.bool(): Boolean = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: unrecognized()

    private fun JsonElement.double(): Double = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: unrecognized()

    private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: unrecognized()

    private fun JsonElement.arr(): JsonArray = this as? JsonArray ?: unrecognized()
}
