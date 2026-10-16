package app.benbow.core.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The tag registry as files of the export and the server's mirror (7.9 T5, ADR-032), so tags and categories read
 * without the app: `tags.json` has everything as stored (ids, names, categories, descriptions, colors, emoji, the
 * archive, merges, the trash) and `tags.md` the same to read: the categories in their order with their tags, then the
 * tags without one, the archive, merges and the trash. Notes keep naming their tags in their own front matter.
 * [written]: tag id to the name its entries hold, for tags that have no name of their own (tags from before ids).
 */
object TagBook {
    const val JSON_FILE = "tags.json"
    const val MARKDOWN_FILE = "tags.md"

    private val json = Json { prettyPrint = true }

    class Category(val id: String, val name: String, val description: String?, val color: String?, val emoji: String?, val inTrash: Boolean)

    class Tag(
        val id: String,
        val name: String,
        val category: String?,
        val description: String?,
        val color: String?,
        val archived: Boolean,
        val into: String?,
        val inTrash: Boolean,
    )

    class Book(val categories: List<Category>, val tags: List<Tag>)

    fun read(registry: Map<String, String?>, written: Map<String, String>): Book {
        fun prefixed(prefix: String) = TagFields.prefixed(registry, prefix)
        val reading = TagNames.of(registry)
        val categoryNames = TagFields.categoryNames(registry)
        val categoryTrash = prefixed(TagFields.CATEGORY_TRASH).mapValues { it.value.toLongOrNull() ?: 0 }
        val descriptions = prefixed(TagFields.CATEGORY_DESCRIPTION)
        val colors = prefixed(TagFields.CATEGORY_COLOR)
        val emoji = prefixed(TagFields.CATEGORY_EMOJI)
        val categories = (
            TagFields.categoryOrder(registry) + categoryTrash.entries.sortedByDescending {
                it.value
            }.map { it.key }
            ).map { id ->
            Category(id, categoryNames[id] ?: id, descriptions[id], colors[id], emoji[id], id in categoryTrash)
        }
        val links = prefixed(TagFields.CATEGORY).mapValues { TagFields.categoryId(it.value) }
        val tagDescriptions = prefixed(TagFields.DESCRIPTION)
        val tagColors = prefixed(TagFields.COLOR)
        val archived = prefixed(TagFields.ARCHIVED).keys
        val ids =
            reading.names.keys + reading.into.keys + reading.trashed + links.keys + tagDescriptions.keys + tagColors.keys + archived +
                written.keys
        val tags = ids.filter { it.isNotEmpty() }.map { id ->
            Tag(
                id = id,
                name = reading.names[id] ?: written[id] ?: id,
                category = links[id],
                description = tagDescriptions[id],
                color = tagColors[id],
                archived = id in archived,
                into = reading.into[id],
                inTrash = id in reading.trashed,
            )
        }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        return Book(categories, tags)
    }

    /** `tags.json`: every category and tag as stored. */
    fun json(book: Book): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", JsonPrimitive("benbow-tags/1"))
            put(
                "categories",
                JsonArray(
                    book.categories.map { c ->
                        buildJsonObject {
                            put("id", JsonPrimitive(c.id))
                            put("name", JsonPrimitive(c.name))
                            c.description?.let { put("description", JsonPrimitive(it)) }
                            c.color?.let { put("color", JsonPrimitive(it)) }
                            c.emoji?.let { put("emoji", JsonPrimitive(it)) }
                            put("trash", JsonPrimitive(c.inTrash))
                        }
                    },
                ),
            )
            put(
                "tags",
                JsonArray(
                    book.tags.map { t ->
                        buildJsonObject {
                            put("id", JsonPrimitive(t.id))
                            put("name", JsonPrimitive(t.name))
                            t.category?.let { put("category", JsonPrimitive(it)) }
                            t.description?.let { put("description", JsonPrimitive(it)) }
                            t.color?.let { put("color", JsonPrimitive(it)) }
                            put("archived", JsonPrimitive(t.archived))
                            t.into?.let { put("merged_into", JsonPrimitive(it)) }
                            put("trash", JsonPrimitive(t.inTrash))
                        }
                    },
                ),
            )
        },
    ) + "\n"

    /** `tags.md`: the same to read, as the app shows it. */
    fun markdown(book: Book): String = buildString {
        val reading =
            TagNames(
                book.tags.associate {
                    it.id to it.name
                },
                book.tags.mapNotNull { t -> t.into?.let { t.id to it } }.toMap(),
                book.tags.filter { it.inTrash }.map { it.id }.toSet(),
            )
        val shown = book.categories.filterNot { it.inTrash }.map { it.id }.toSet()
        val live = book.tags.filter { it.into == null && !it.inTrash }
        fun line(tag: Tag, category: Boolean = false) {
            append("- **").append(tag.name).append("**")
            tag.color?.let { append(" (").append(it).append(")") }
            if (category) tag.category?.let { id -> book.categories.firstOrNull { it.id == id }?.let { append(" · ").append(it.name) } }
            tag.description?.let { append(" — ").append(it.oneLine()) }
            appendLine()
        }
        appendLine("# Tags")
        appendLine()
        appendLine("Every tag and tag category of the journal, as the app shows them; `tags.json` has the same for programs.")
        appendLine("Notes name their tags in their own front matter.")
        for (category in book.categories.filterNot { it.inTrash }) {
            appendLine()
            append("## ").append(listOfNotNull(category.emoji, category.name).joinToString(" "))
            category.color?.let { append(" (").append(it).append(")") }
            appendLine()
            category.description?.let { appendLine().appendLine(it.oneLine()) }
            val mine = live.filter { !it.archived && it.category == category.id }
            appendLine()
            if (mine.isEmpty()) appendLine("_No tags yet._") else mine.forEach { line(it) }
        }
        val none = live.filter { !it.archived && it.category !in shown }
        if (none.isNotEmpty()) {
            appendLine().appendLine("## No category").appendLine()
            none.forEach { line(it) }
        }
        val archived = live.filter { it.archived }
        if (archived.isNotEmpty()) {
            appendLine().appendLine("## Archived").appendLine()
            archived.forEach { line(it, category = true) }
        }
        val merged = book.tags.filter { it.into != null && !it.inTrash }
        if (merged.isNotEmpty()) {
            appendLine().appendLine("## Merged").appendLine()
            merged.forEach { t -> appendLine("- **${t.name}** → **${reading.names[reading.root(t.id)] ?: reading.root(t.id)}**") }
        }
        val trashedTags = book.tags.filter { it.inTrash }
        val trashedCategories = book.categories.filter { it.inTrash }
        if (trashedTags.isNotEmpty() || trashedCategories.isNotEmpty()) {
            appendLine().appendLine("## In the trash").appendLine()
            trashedCategories.forEach { appendLine("- Category **${it.name}**") }
            trashedTags.forEach { appendLine("- Tag **${it.name}**") }
        }
    }

    private fun String.oneLine() = replace(Regex("""\s*\n\s*"""), " ").trim()
}
