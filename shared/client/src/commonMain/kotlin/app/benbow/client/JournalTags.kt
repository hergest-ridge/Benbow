package app.benbow.client

import app.benbow.client.db.TagCounts
import app.benbow.core.content.TagNames
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Tags and tag categories as things of their own (7.9, ADR-032). Putting a tag on an entry or taking it off is a field
// of that entry; everything else (names, descriptions, colors, categories, merges, the archive, the trash) is a field of
// the tag registry, so entries never change for it and every step can be taken back.

/** Names, categories, colors and the rest of tags and categories by id. */
fun JournalRepository.tagRegistry(): Flow<TagRegistry> = q.selectEntry(TAG_REGISTRY_ID).asFlow().mapToOneOrNull(context).map { registry ->
    registry?.let { TagRegistry.of(decodeFields(it.fields)) } ?: TagRegistry()
}.distinctUntilChanged()

internal fun JournalRepository.registry(): TagRegistry =
    q.selectEntry(TAG_REGISTRY_ID).executeAsOneOrNull()?.let { TagRegistry.of(decodeFields(it.fields)) } ?: TagRegistry()

/**
 * Every tag outside the trash as it reads (merged ones counted in the tag they went into), most used first, with tags
 * that have a name of their own but no entries yet: suggestions, filters, the tag list.
 */
fun JournalRepository.tags(): Flow<List<TagCount>> = combine(q.tagCounts().asFlow().mapToList(context), tagRegistry()) { rows, registry ->
    tagCounts(rows, registry) { !registry.inTrash(it) }
}

/** The tags in the trash, the latest first. */
fun JournalRepository.trashedTags(): Flow<List<TagCount>> = combine(q.tagCounts().asFlow().mapToList(context), tagRegistry()) {
        rows,
        registry,
    ->
    tagCounts(rows, registry) { it in registry.read.trashed }.sortedByDescending { registry.trashedAt[it.id] ?: 0 }
}

private fun tagCounts(rows: List<TagCounts>, registry: TagRegistry, keep: (String) -> Boolean): List<TagCount> {
    val written = rows.groupBy { it.key.removePrefix(TAG_PREFIX) }
    val roots = (written.keys + registry.names.keys).filter { it.isNotEmpty() && registry.root(it) == it && keep(it) }
    return roots.mapNotNull { id ->
        val family = registry.family(id)
        val own = written[id].orEmpty()
        // Entries may hold a name in different cases: the registry's own wins, else the one most used.
        val name = registry.names[id] ?: own.sortedWith(compareByDescending<TagCounts> { it.uses }.thenBy { it.name }).firstOrNull()?.name
        val uses = family.sumOf { key -> written[key].orEmpty().sumOf { it.uses } }.toInt()
        name?.takeIf { it.isNotEmpty() }?.let { TagCount(id, it, uses, registry.category(id), id in registry.archived) }
    }.sortedWith(compareByDescending<TagCount> { it.uses }.thenBy { it.name })
}

/**
 * Puts a tag on the entry: [tagId] when it was picked from a list, else the tag with this name (any case, a merged one
 * leads to the tag it went into), else a tag from before ids whose key is the name, else a new tag with an id of its
 * own. Returns the tag's id; null when the name is empty.
 */
fun JournalRepository.addTag(entryId: String, name: String, tagId: String? = null): String? = db.transactionWithResult {
    val clean = tagName(name)
    if (clean.isEmpty()) return@transactionWithResult null
    val registry = registry()
    val id = tagId?.let(registry::root) ?: registry.named(clean) ?: olderTag(clean, registry) ?: newTag(clean)
    setField(entryId, TAG_PREFIX + id, registry.names[id] ?: clean)
    id
}

/** A tag from before ids whose key is this name (its id), unless the registry named it otherwise since or it's in the trash. */
private fun JournalRepository.olderTag(name: String, registry: TagRegistry): String? = name.lowercase().takeIf { id ->
    id !in registry.names &&
        !registry.inTrash(id) &&
        (id in registry.own || id in registry.tagCategories || q.tagKeyInUse(TAG_PREFIX + id).executeAsOneOrNull() != null)
}?.let(registry::root)

private fun JournalRepository.newTag(name: String): String = newId().also { setRegistryField(TagNames.NAME_PREFIX + it, name) }

/** Takes the tag off the entry, with every tag merged into it that the entry carries. */
fun JournalRepository.removeTag(entryId: String, tagId: String) {
    db.transaction {
        val entry = q.selectEntry(entryId).executeAsOneOrNull() ?: return@transaction
        val family = registry().family(tagId)
        tagsOf(decodeFields(entry.fields)).filter { it.id in family }.forEach { setField(entryId, TAG_PREFIX + it.id, null) }
    }
}

/** A new tag with no entries yet (from the tags screen); null when the name is empty. */
fun JournalRepository.createTag(name: String, category: String? = null): String? = tagName(name).takeIf { it.isNotEmpty() }?.let { clean ->
    db.transactionWithResult { newTag(clean).also { if (category != null) setTagCategory(it, category) } }
}

/** Renames the tag everywhere at once: entries keep its id, so nothing else changes. False when the name is empty. */
fun JournalRepository.renameTag(tagId: String, name: String): Boolean {
    val clean = tagName(name).takeIf { it.isNotEmpty() } ?: return false
    setRegistryField(TagNames.NAME_PREFIX + tagId, clean)
    return true
}

/** A few words about what the tag means; blank removes them. */
fun JournalRepository.describeTag(tagId: String, text: String?) =
    setRegistryField(DESCRIPTION_PREFIX + tagId, text?.trim()?.takeIf { it.isNotEmpty() })

/** Archived tags stay on their entries and in search, out of suggestions. */
fun JournalRepository.archiveTag(tagId: String, archived: Boolean) =
    setRegistryField(ARCHIVED_PREFIX + tagId, if (archived) "true" else null)

/** Puts a tag into a category, by the category's id (null = none). */
fun JournalRepository.setTagCategory(tagId: String, category: String?) =
    setRegistryField(CATEGORY_PREFIX + tagId, category?.let(::categoryId)?.takeIf { it.isNotEmpty() })

/** A tag's own color, one of [SWATCHES] (null = as its category). */
fun JournalRepository.setTagColor(tagId: String, color: String?) = setRegistryField(COLOR_PREFIX + tagId, color?.takeIf { it in SWATCHES })

/**
 * Puts the tag in the trash: it leaves every entry's chips, the lists and the suggestions, while entries keep it, so
 * [restoreTag] brings it back exactly; Бережно's rules still see it. The tags merged into it go with it.
 */
fun JournalRepository.trashTag(tagId: String) = setRegistryField(TagNames.TRASH_PREFIX + tagId, now().toString())

fun JournalRepository.restoreTag(tagId: String) = setRegistryField(TagNames.TRASH_PREFIX + tagId, null)

/**
 * Merges [source] into [target]: entries with either read as [target], and typing the source's name finds the target.
 * Entries keep what they carry, so [splitTag] undoes it. Both get a name of their own first (from their entries), so
 * they read the same on every device. False when it would merge a tag into itself or either is in the trash.
 */
fun JournalRepository.mergeTags(source: String, target: String): Boolean = db.transactionWithResult {
    val registry = registry()
    val into = registry.root(target)
    if (registry.root(source) == into || registry.inTrash(source) || registry.inTrash(into)) return@transactionWithResult false
    for (id in listOf(source, into)) {
        if (id !in
            registry.names
        ) {
            q.tagNameOnEntries(TAG_PREFIX + id).executeAsOneOrNull()?.let { setRegistryField(TagNames.NAME_PREFIX + id, it) }
        }
    }
    setRegistryField(TagNames.INTO_PREFIX + source, into)
    true
}

/** Splits a merged tag off again: its entries read as it once more. */
fun JournalRepository.splitTag(tagId: String) = setRegistryField(TagNames.INTO_PREFIX + tagId, null)

// Categories

/** A new category; null when the name is empty. */
fun JournalRepository.createCategory(name: String): String? = name.trim().takeIf { it.isNotEmpty() }?.let { clean ->
    db.transactionWithResult { newId().also { setRegistryField(CATEGORY_NAME_PREFIX + it, clean) } }
}

fun JournalRepository.renameCategory(category: String, name: String): Boolean {
    val clean = name.trim().takeIf { it.isNotEmpty() } ?: return false
    setRegistryField(CATEGORY_NAME_PREFIX + categoryId(category), clean)
    return true
}

fun JournalRepository.describeCategory(category: String, text: String?) =
    setRegistryField(CATEGORY_DESCRIPTION_PREFIX + categoryId(category), text?.trim()?.takeIf { it.isNotEmpty() })

/** An emoji shown with the category's name; blank removes it. */
fun JournalRepository.setCategoryEmoji(category: String, emoji: String?) =
    setRegistryField(CATEGORY_EMOJI_PREFIX + categoryId(category), emoji?.trim()?.takeIf { it.isNotEmpty() })

/** A category's color by its id, one of [SWATCHES] (null = the default). */
fun JournalRepository.setCategoryColor(category: String, color: String?) =
    setRegistryField(CATEGORY_COLOR_PREFIX + categoryId(category), color?.takeIf { it in SWATCHES })

/** The categories in this order (each keeps its place on its own, so two devices at once give a mixed but shared order). */
fun JournalRepository.setCategoryOrder(categories: List<String>) {
    db.transaction { categories.forEachIndexed { i, id -> setRegistryField(CATEGORY_ORDER_PREFIX + categoryId(id), i.toString()) } }
}

/**
 * Puts a category in the trash: its tags show without a category until [restoreCategory]; their links stay, and
 * Бережно's rules on it still hold. «Эмоции» can't go (check-ins put feelings there): false.
 */
fun JournalRepository.trashCategory(category: String): Boolean {
    val id = categoryId(category)
    if (id == EMOTIONS) return false
    setRegistryField(CATEGORY_TRASH_PREFIX + id, now().toString())
    return true
}

fun JournalRepository.restoreCategory(category: String) = setRegistryField(CATEGORY_TRASH_PREFIX + categoryId(category), null)

// Бережно for one tag or category (ADR-031), from its editor

/** The rule on this tag or category ([GentleRule.TAG] or [GentleRule.CATEGORY] and its id), if there is one. */
fun GentleRules.ruleFor(kind: String, id: String): GentleRule? = rules.firstOrNull { it.kind == kind && it.key == id }

/** Sets the level of the rule on this tag or category, or makes one; «Видно» on a rule keeps it, switched off. */
fun JournalRepository.setGentleLevel(kind: String, id: String, name: String, level: String) {
    db.transaction {
        val fields = q.selectEntry(GENTLE_REGISTRY_ID).executeAsOneOrNull()?.let { decodeFields(it.fields) }.orEmpty()
        val rule = GentleRules.of(fields, emptyMap(), "").ruleFor(kind, id)
        when {
            rule != null -> setGentleRule(rule.copy(level = level))
            level != GentleRule.OFF -> setGentleRule(GentleRule(newRuleId(), name, kind, value = id, level = level))
        }
    }
}
