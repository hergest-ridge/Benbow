package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException
import app.benbow.core.content.toPlainText

/**
 * The on-device full-text index (`entry_search`): title, all text and tags of every entry. Updated in
 * the same transaction as the entry; rebuilt from scratch when [VERSION] changes.
 */
internal object SearchIndex {
    const val VERSION = "2"
    private const val VERSION_KEY = "search.version"

    fun update(db: BenbowDb, id: String) {
        val q = db.benbowQueries
        q.unindexEntry(id)
        val entry = q.selectEntry(id).executeAsOneOrNull() ?: return
        val text = try {
            DocumentCodec.decode(entry.body).toPlainText(includeSpoilers = true)
        } catch (_: DocumentFormatException) {
            ""
        }
        q.indexEntry(id, fold(entry.title), fold(text), fold(tagsOf(decodeFields(entry.fields)).joinToString(" ")))
    }

    /** unicode61 folds case everywhere but diacritics only in Latin script: "ёлка" must still match "елка". */
    private fun fold(text: String) = text.replace('ё', 'е').replace('Ё', 'Е')

    fun rebuildIfNeeded(db: BenbowDb) {
        val settings = Settings(db)
        if (settings.get(VERSION_KEY) == VERSION) return
        db.transaction {
            db.benbowQueries.clearIndex()
            db.benbowQueries.allEntryIds().executeAsList().forEach { update(db, it) }
            settings.put(VERSION_KEY, VERSION)
        }
    }

    /**
     * An FTS query from what the user typed: every word must match, as a prefix ("мор" finds "море").
     * Only letters and digits pass, so FTS syntax in the input can't break or widen the query.
     */
    fun match(input: String): String? = fold(input).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
        ?.joinToString(" ") { "$it*" }
}
