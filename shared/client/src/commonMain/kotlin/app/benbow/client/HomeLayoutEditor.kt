package app.benbow.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Home's editor (ADR-034): a change shows at once in [layout] and is written behind it, one by one in order, each to the
 * layout as stored by then, so what another device did meanwhile stays. [close] it when the editor goes; what is queued
 * is still written.
 */
class HomeLayoutEditor(private val repo: JournalRepository, scope: CoroutineScope, start: HomeLayout) {
    private val state = MutableStateFlow(start)
    val layout: StateFlow<HomeLayout> = state
    private val writes = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (write in writes) write() }
    }

    fun move(id: String, by: Int) = edit { it.moved(id, by) }

    /** Takes [id] off Home: the block and its place, for "Вернуть". */
    fun remove(id: String): Pair<HomeBlock, Int>? {
        val at = state.value.blocks.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        val block = state.value.blocks[at]
        edit { it.minusBlock(id) }
        return block to at
    }

    fun put(block: HomeBlock, at: Int) = edit { it.plusBlock(block, at) }

    fun resize(id: String, size: BlockSize) = change(id) { it.copy(size = size.value) }

    /** One of a block's settings; null takes it away. */
    fun configure(id: String, key: String, value: String?) =
        change(id) { block -> block.copy(config = if (value == null) block.config - key else block.config + (key to value)) }

    /**
     * A block of [kind] after [after] (at the end without it). Its id is the kind's own while that is free, so the same
     * block added on two devices is one.
     */
    fun add(kind: HomeBlockType, after: String?, config: Map<String, String> = emptyMap()): HomeBlock {
        val blocks = state.value.blocks
        val id = kind.value.takeIf { kind.once && blocks.none { it.id == kind.value } } ?: repo.newId()
        val block = HomeBlock.of(kind, id, config)
        put(block, blocks.indexOfFirst { it.id == after }.let { if (it < 0) blocks.size else it + 1 })
        return block
    }

    /** Home as it comes: the blocks it had, for "Вернуть". */
    fun reset(): List<HomeBlock> {
        val before = state.value.blocks
        if (!state.value.custom) return before
        state.value = HomeLayout.DEFAULT
        writes.trySend { repo.resetHomeLayout() }
        return before
    }

    fun restore(blocks: List<HomeBlock>) = edit { blocks }

    fun close() {
        writes.close()
    }

    private fun change(id: String, change: (HomeBlock) -> HomeBlock) = edit { blocks -> blocks.map { if (it.id == id) change(it) else it } }

    private fun edit(change: (List<HomeBlock>) -> List<HomeBlock>) {
        val now = state.value
        val next = change(now.blocks)
        if (next == now.blocks) return
        state.value = HomeLayout(next, custom = true)
        writes.trySend { repo.editHomeLayout(change) }
    }
}
