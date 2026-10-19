package app.benbow.core.content

import app.benbow.core.text.ATOM_CHAR
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

private val alphabet =
    listOf("a", "Я", "ю", " ", "*", "_", "#", "\\", "\"", "<", "`", "1.", "😀", SOFT_BREAK.toString(), ATOM_CHAR.toString())
private val defPool = listOf(
    MarkDef.Link("", "https://example.com/a b"),
    MarkDef.EntryLink("", "entry-1", "blk"),
    MarkDef.Color("", "red"),
    MarkDef.Highlight("", "yellow"),
    MarkDef.Size("", "large"),
)
private val callouts = listOf(null, CalloutRef("c1", "blue", "💡"), CalloutRef("c2", "red", "⚓"))

/** Random canonical document; adjacent code blocks never share a callout (the editor would merge them). */
internal fun randomDocument(rnd: Random): Document {
    val blocks = ArrayList<Block>()
    repeat(rnd.nextInt(1, 9)) { i ->
        val key = "b$i"
        val previousCode = blocks.lastOrNull() as? CodeBlock
        when (rnd.nextInt(13)) {
            in 0..6 -> blocks.add(randomText(rnd, key))

            7, 8 -> {
                val callout = callouts.random(rnd)
                if (previousCode != null && previousCode.callout == callout) {
                    blocks.add(randomText(rnd, key))
                } else {
                    val lines = List(rnd.nextInt(1, 4)) { randomString(rnd).replace(SOFT_BREAK.toString(), "") }
                    blocks.add(CodeBlock(key, lines.joinToString("\n"), listOf(null, "kotlin").random(rnd), callout))
                }
            }

            9 -> blocks.add(DividerBlock(key, listOf(null, "dots").random(rnd)))

            10 -> blocks.add(AttachmentBlock(key, "att-$i", listOf(null, 0.5, 0.3333).random(rnd), listOf(null, "фото").random(rnd)))

            11 -> blocks.add(
                LinkCardBlock(
                    key,
                    "https://example.com/$i?q=a b",
                    title = listOf(null, randomString(rnd).ifBlank { "t" }).random(rnd),
                    description = listOf(null, "о «маяках»").random(rnd),
                    site = listOf(null, "Хабр").random(rnd),
                    image = listOf(null, "a".repeat(64)).random(rnd),
                    view = listOf(null, "large").random(rnd),
                    fetched = listOf(null, "2026-10-19").random(rnd),
                ),
            )

            else -> blocks.add(
                UnknownBlock(
                    key,
                    buildJsonObject {
                        put("_key", key)
                        put("_type", "future")
                        put("x", rnd.nextInt(100))
                    },
                ),
            )
        }
    }
    return DocumentCodec.canonicalize(Document(blocks))
}

private fun randomText(rnd: Random, key: String): TextBlock {
    val listItem = listOf(null, null, "bullet", "number", "check").random(rnd)
    val defs = defPool.shuffled(rnd).take(rnd.nextInt(0, 3)).mapIndexed { i, d -> d.rekey("d$i") }
    val spans = List(rnd.nextInt(0, 5)) {
        val decorators = DECORATORS.filter { rnd.nextInt(4) == 0 }
        val annotations = defs.filter { rnd.nextBoolean() }.map { it.key }
        Span(randomString(rnd), decorators + annotations)
    }
    return TextBlock(
        key = key,
        style = TEXT_STYLES.random(rnd),
        listItem = listItem,
        level = if (listItem != null) rnd.nextInt(1, 4) else null,
        checked = if (listItem == "check") rnd.nextBoolean() else null,
        align = listOf(null, "center", "end").random(rnd),
        callout = callouts.random(rnd),
        children = spans,
        markDefs = defs,
    )
}

private fun randomString(rnd: Random): String = buildString { repeat(rnd.nextInt(0, 6)) { append(alphabet.random(rnd)) } }

private fun MarkDef.rekey(key: String): MarkDef = when (this) {
    is MarkDef.Link -> copy(key = key)
    is MarkDef.EntryLink -> copy(key = key)
    is MarkDef.Color -> copy(key = key)
    is MarkDef.Highlight -> copy(key = key)
    is MarkDef.Size -> copy(key = key)
}
