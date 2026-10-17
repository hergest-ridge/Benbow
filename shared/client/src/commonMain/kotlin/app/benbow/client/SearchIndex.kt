package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.audio.TrackTags
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException
import app.benbow.core.content.TagNames
import app.benbow.core.content.toPlainText

/**
 * The on-device full-text index (`entry_search`): title, all text (with what was said in voice notes, the names of
 * attachments and the tags of sound files) and tags (as they read, ADR-032) of every entry, plus the Russian stems of
 * their words.
 * Updated in the same transaction as the entry; rebuilt from scratch when [VERSION] changes.
 */
internal object SearchIndex {
    const val VERSION = "4"
    private const val VERSION_KEY = "search.version"
    private val WORD = Regex("""[\p{L}\p{N}]+""")

    /** [tags]: how tags read (the tag registry), read once by callers that index many entries. */
    fun update(db: BenbowDb, id: String, tags: TagNames = registryTags(db)) {
        val q = db.benbowQueries
        q.unindexEntry(id)
        val entry = q.selectEntry(id).executeAsOneOrNull() ?: return
        val text = try {
            DocumentCodec.decode(entry.body).toPlainText(includeSpoilers = true)
        } catch (_: DocumentFormatException) {
            ""
        }
        val fields = decodeFields(entry.fields)
        val spoken = transcriptsOf(fields).values
        val captions = decodeAttachments(entry.attachments).values.filter { it.present }.mapNotNull { it.caption }
        val tracks = TrackTags.of(fields).values.flatMap { listOfNotNull(it.title, it.artist, it.album) }
        val all = (listOf(text) + spoken + captions + tracks).filter { it.isNotBlank() }.joinToString("\n")
        val names = tags.on(fields).joinToString(" ") { it.second }
        q.indexEntry(id, fold(entry.title), withStems(fold(all)), withStems(fold(names)))
    }

    /** unicode61 folds case everywhere but diacritics only in Latin script: "ёлка" must still match "елка". */
    private fun fold(text: String) = text.replace('ё', 'е').replace('Ё', 'Е')

    /** The text, then the stems that differ from their words: "у моря" also indexes "мор", so "море" and "морем" find it. */
    private fun withStems(text: String): String {
        val stems = WORD.findAll(text).map { it.value.lowercase() }.mapNotNull { word -> RussianStemmer.stem(word).takeIf { it != word } }
            .distinct().toList()
        return if (stems.isEmpty()) text else text + "\n" + stems.joinToString(" ")
    }

    fun rebuildIfNeeded(db: BenbowDb) {
        val settings = Settings(db)
        if (settings.get(VERSION_KEY) == VERSION) return
        db.transaction {
            db.benbowQueries.clearIndex()
            val tags = registryTags(db)
            db.benbowQueries.allEntryIds().executeAsList().forEach { update(db, it, tags) }
            settings.put(VERSION_KEY, VERSION)
        }
    }

    /** Indexes the entries with these tags again, once the registry made them read differently ([TagNames.changed]). */
    fun retag(db: BenbowDb, tags: Set<String>) {
        if (tags.isEmpty()) return
        val read = registryTags(db)
        tags.flatMap { db.benbowQueries.entriesWithTag(TAG_PREFIX + it).executeAsList() }.distinct().forEach { update(db, it, read) }
    }

    private fun registryTags(db: BenbowDb): TagNames =
        db.benbowQueries.selectEntry(TAG_REGISTRY_ID).executeAsOneOrNull()?.let { TagNames.of(decodeFields(it.fields)) } ?: TagNames()

    /** One typed word as FTS: [either] = its stem or the word as a prefix, [prefix] = just the prefix. */
    class Term(val either: String, val prefix: String)

    /**
     * What the user typed, word by word; every word must match. A word matches its stem exactly ("моря" → "мор": "море",
     * "морем", but not "морковь") or its beginning ("казан" while typing "Казань"). Only letters and digits pass, so FTS
     * syntax in the input can't break or widen the query.
     */
    fun terms(input: String): List<Term> = WORD.findAll(fold(input)).map { it.value.lowercase() }.distinct().map { word ->
        val stem = RussianStemmer.stem(word)
        Term(either = if (stem != word) "$stem OR $word*" else "$word*", prefix = "$word*")
    }.toList()
}
