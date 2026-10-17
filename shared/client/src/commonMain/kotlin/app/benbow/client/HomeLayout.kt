package app.benbow.client

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

// Home as blocks (ADR-034): one layout for every device, kept in the system entry `system-home` as LWW fields,
// `block:<id>` per block and `order` (the ids, comma-separated). Until the owner changes anything it has no fields and
// Home is [HomeLayout.DEFAULT], so a better default still reaches them; the first change writes the whole layout.

/** How much of Home's grid a block takes: S half a row, M and L a whole row (L in its larger form), a list a whole row. */
enum class BlockSize(val value: String) {
    S("s"),
    M("m"),
    L("l"),
    List("list"),
    ;

    /** Columns taken in a grid of [columns] (two on a phone). */
    fun span(columns: Int): Int = when (this) {
        S -> 1
        M, L -> minOf(2, columns)
        List -> columns
    }

    companion object {
        fun of(value: String): BlockSize? = entries.firstOrNull { it.value == value }
    }
}

/**
 * The blocks this app can draw, with the sizes each comes in (the first is the one it starts with); a block that isn't
 * [once] may be on Home many times (a tag block per tag).
 */
enum class HomeBlockType(val value: String, val sizes: List<BlockSize>, val once: Boolean = true) {
    Checkin("checkin", listOf(BlockSize.M)),
    Prompt("prompt", listOf(BlockSize.M)),
    Pinned("pinned", listOf(BlockSize.List)),
    Today("today", listOf(BlockSize.List)),
    Drafts("drafts", listOf(BlockSize.List)),
    OnThisDay("on-this-day", listOf(BlockSize.List, BlockSize.M, BlockSize.L)),
    Favorite("favorite", listOf(BlockSize.M, BlockSize.S)),
    Stats("stats", listOf(BlockSize.M, BlockSize.S)),

    /** A tag's entries as a small gallery (config [HomeBlock.TAG], [HomeBlock.ORDER]). */
    Tag("tag", listOf(BlockSize.M, BlockSize.S, BlockSize.L), once = false),
    ;

    companion object {
        fun of(value: String): HomeBlockType? = entries.firstOrNull { it.value == value }
    }
}

data class HomeBlock(
    val id: String,
    /** A [HomeBlockType]'s value; any other (from a newer app, or a field that didn't read) is kept as it is, not drawn. */
    val type: String,
    /** A [BlockSize]'s value, the same way. */
    val size: String,
    /** The block's own settings (a tag's id, how many entries...). New settings go here, so older apps keep them. */
    val config: Map<String, String> = emptyMap(),
) {
    val kind: HomeBlockType? get() = HomeBlockType.of(type)

    /** Its size, when this app can draw the block at that size. */
    val blockSize: BlockSize? get() = BlockSize.of(size)?.takeIf { kind?.sizes?.contains(it) == true }

    internal fun encode(): String = ClientJson.encodeToString(Stored.serializer(), Stored(type, size, config))

    @Serializable
    private class Stored(val type: String = "", val size: String = "", val config: Map<String, String> = emptyMap())

    companion object {
        fun of(type: HomeBlockType, id: String = type.value, config: Map<String, String> = emptyMap()) =
            HomeBlock(id, type.value, type.sizes.first().value, config)

        /** A tag block's tag (its id) and order ([RANDOM]: shuffled once a day; newest first otherwise). */
        const val TAG = "tag"
        const val ORDER = "order"
        const val RANDOM = "random"

        /** A stats block's figures, comma-separated [StatFigure] values (see [figures]). */
        const val FIGURES = "figures"

        /** On this day with [MONTHS]: also a month and half a year ago (a journal's first year has no years ago yet). */
        const val ALSO = "also"
        const val MONTHS = "months"

        internal fun decode(id: String, json: String): HomeBlock {
            val stored = runCatching { ClientJson.decodeFromString(Stored.serializer(), json) }.getOrElse { Stored() }
            return HomeBlock(id, stored.type, stored.size, stored.config)
        }
    }
}

/** The owner's blocks in their order; [custom] once anything was changed (the fields exist). */
data class HomeLayout(val blocks: List<HomeBlock>, val custom: Boolean) {
    companion object {
        /** Today's Home as it was before blocks: now, then what's unfinished, then looks back, then soft stats. */
        val DEFAULT = HomeLayout(
            listOf(
                HomeBlockType.Checkin,
                HomeBlockType.Prompt,
                HomeBlockType.Pinned,
                HomeBlockType.Today,
                HomeBlockType.Drafts,
                HomeBlockType.OnThisDay,
                HomeBlockType.Favorite,
                HomeBlockType.Stats,
            ).map { HomeBlock.of(it) },
            custom = false,
        )

        /** From the registry's fields: blocks in `order` first, then any it doesn't name yet (added on another device). */
        internal fun of(fields: Map<String, String?>): HomeLayout {
            val stored = fields.mapNotNull { (key, value) ->
                if (!key.startsWith(BLOCK_PREFIX) || value == null) null else HomeBlock.decode(key.removePrefix(BLOCK_PREFIX), value)
            }.associateBy { it.id }
            val order = fields[ORDER_FIELD]
            if (order == null && stored.isEmpty()) return DEFAULT
            val ids = order.orEmpty().split(',').filter { it.isNotEmpty() }.distinct()
            return HomeLayout(ids.mapNotNull(stored::get) + stored.values.filter { it.id !in ids }.sortedBy { it.id }, custom = true)
        }
    }
}

/**
 * Home's rows: blocks in their order, as many in a row as fit in [columns]; a row with room left is shared out among
 * its blocks (a lone S is drawn as wide as the row). Blocks this app can't draw take no room.
 */
fun packHome(blocks: List<HomeBlock>, columns: Int = 2): List<List<HomeBlock>> = packRows(blocks, columns) { it.blockSize?.span(columns) }

/** Items in order, a new row when the next one's span doesn't fit; items without a span are left out. */
internal fun <T> packRows(items: List<T>, columns: Int, span: (T) -> Int?): List<List<T>> {
    val rows = mutableListOf<List<T>>()
    var row = mutableListOf<T>()
    var used = 0
    items.forEach { item ->
        val width = span(item) ?: return@forEach
        if (used + width > columns && row.isNotEmpty()) {
            rows += row
            row = mutableListOf()
            used = 0
        }
        row += item
        used += width
    }
    if (row.isNotEmpty()) rows += row
    return rows
}

fun JournalRepository.homeLayout(): Flow<HomeLayout> = q.selectEntry(HOME_REGISTRY_ID).asFlow().mapToOneOrNull(context).map { registry ->
    HomeLayout.of(registry?.let { decodeFields(it.fields) }.orEmpty())
}.distinctUntilChanged()

/**
 * Saves what the editor made of [from]. Only what changed is written, so a block another device added meanwhile (not in
 * [from]) stays, and blocks this app can't draw ride along untouched. The first save writes the whole layout.
 */
fun JournalRepository.saveHomeLayout(from: HomeLayout, to: List<HomeBlock>) {
    val before = if (from.custom) from.blocks.associateBy { it.id } else emptyMap()
    db.transaction {
        to.forEach { block -> if (before[block.id] != block) setRegistryField(BLOCK_PREFIX + block.id, block.encode(), HOME_REGISTRY_ID) }
        val kept = to.mapTo(HashSet()) { it.id }
        if (from.custom) from.blocks.filter { it.id !in kept }.forEach { setRegistryField(BLOCK_PREFIX + it.id, null, HOME_REGISTRY_ID) }
        if (!from.custom || from.blocks.map { it.id } != to.map { it.id }) {
            setRegistryField(ORDER_FIELD, to.joinToString(",") { it.id }, HOME_REGISTRY_ID)
        }
    }
}

/** "Вернуть обычную главную": every field emptied, so Home is the default again (and follows it). */
fun JournalRepository.resetHomeLayout() {
    db.transaction {
        val fields = q.selectEntry(HOME_REGISTRY_ID).executeAsOneOrNull()?.let { decodeFields(it.fields) } ?: return@transaction
        fields.forEach { (key, value) ->
            if (value != null && (key.startsWith(BLOCK_PREFIX) || key == ORDER_FIELD)) setRegistryField(key, null, HOME_REGISTRY_ID)
        }
    }
}

/** Applies [edit] to the layout as it is stored now, in one transaction; nothing is written when it changes nothing. */
fun JournalRepository.editHomeLayout(edit: (List<HomeBlock>) -> List<HomeBlock>) {
    db.transaction {
        val from = HomeLayout.of(q.selectEntry(HOME_REGISTRY_ID).executeAsOneOrNull()?.let { decodeFields(it.fields) }.orEmpty())
        val to = edit(from.blocks)
        if (to != from.blocks) saveHomeLayout(from, to)
    }
}

/** [id] moved past the next block this app draws, earlier ([by] < 0) or later; blocks it can't draw keep their places. */
fun List<HomeBlock>.moved(id: String, by: Int): List<HomeBlock> {
    val from = indexOfFirst { it.id == id }
    if (from < 0 || by == 0) return this
    val step = if (by < 0) -1 else 1
    var to = from + step
    while (to in indices && this[to].blockSize == null) to += step
    if (to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

fun List<HomeBlock>.minusBlock(id: String): List<HomeBlock> = filter { it.id != id }

/** [block] put in at [at] (a block with its id stays as it is). */
fun List<HomeBlock>.plusBlock(block: HomeBlock, at: Int): List<HomeBlock> =
    if (any { it.id == block.id }) this else toMutableList().apply { add(at.coerceIn(0, size), block) }

/** The kinds that can be added: a block that comes [HomeBlockType.once] only while Home doesn't have it. */
fun HomeLayout.addable(): List<HomeBlockType> = HomeBlockType.entries.filter { kind -> !kind.once || blocks.none { it.type == kind.value } }

/** Home's layout registry: a system entry like the tag registry (ADR-034). */
internal const val HOME_REGISTRY_ID = "system-home"
internal const val BLOCK_PREFIX = "block:"
internal const val ORDER_FIELD = "order"
