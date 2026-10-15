package app.benbow.client

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A synchronous SQLDelight driver over sql.js, so the client's code runs in the browser unchanged (ADR-029). The
 * database lives in this tab's memory; shortly after each change the whole file is saved to IndexedDB (a few
 * milliseconds per 50 MB). What isn't saved yet is at most the last moment's typing, and [unsaved] lets the page
 * warn before closing.
 */
class BrowserSqlDriver private constructor(private val shelf: FileShelf, private val key: String, private var db: JsAny) : SqlDriver {
    private var transaction: Transaction? = null
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var dirty = false
    private var force = false
    private var saving: Job? = null
    private var lastChange: TimeMark = TimeSource.Monotonic.markNow()
    private var firstUnsaved: TimeMark? = null
    private var storing = 0

    /** Changes not in browser storage yet. */
    val unsaved: Boolean get() = dirty || saving != null || storing > 0

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = statement(sql, binders) { statement ->
        if (sql.contains("RETURNING", ignoreCase = true)) changed() // the only writes that return rows
        mapper(Cursor(statement))
    }

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> =
        statement(sql, binders) { statement ->
            while (step(statement)) Unit
            changed()
            QueryResult.Value(rowsModified(db).toLong())
        }

    private inline fun <R> statement(sql: String, noinline binders: (SqlPreparedStatement.() -> Unit)?, block: (JsAny) -> R): R {
        val statement = prepare(db, sql)
        try {
            if (binders != null) bind(statement, Binder().apply(binders).values)
            return block(statement)
        } finally {
            free(statement)
        }
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        val enclosing = transaction
        if (enclosing == null) execute(null, "BEGIN IMMEDIATE", 0)
        return QueryResult.Value(Transaction(enclosing).also { transaction = it })
    }

    override fun currentTransaction(): Transacter.Transaction? = transaction

    private inner class Transaction(override val enclosingTransaction: Transaction?) : Transacter.Transaction() {
        public override fun endTransaction(successful: Boolean): QueryResult<Unit> {
            if (enclosingTransaction == null) execute(null, if (successful) "COMMIT" else "ROLLBACK", 0)
            transaction = enclosingTransaction
            return QueryResult.Unit
        }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { listeners.getOrPut(it) { mutableSetOf() }.add(listener) }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { listeners[it]?.remove(listener) }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        queryKeys.flatMapTo(LinkedHashSet()) { listeners[it].orEmpty() }.forEach(Query.Listener::queryResultsChanged)
    }

    override fun close() = Unit

    /**
     * Saves once changes pause: they come in bursts (a sync page, a draft every few hundred ms while typing) and each
     * snapshot is the whole file. While changes keep coming, a snapshot still goes out every [MAX_WAIT].
     */
    private fun changed() {
        dirty = true
        lastChange = TimeSource.Monotonic.markNow()
        if (firstUnsaved == null) firstUnsaved = lastChange
        if (saving == null) {
            saving = scope.launch { save(patient = true) }.also { job -> job.invokeOnCompletion { if (saving === job) saving = null } }
        }
    }

    /** Saves what's pending now (the page is being hidden, a test checks the stored file). */
    suspend fun flush() {
        saving?.cancelAndJoin()
        save(patient = false)
    }

    private suspend fun save(patient: Boolean) {
        while (dirty) {
            if (patient) {
                while (lastChange.elapsedNow() < QUIET && firstUnsaved?.let { it.elapsedNow() < MAX_WAIT } != false) delay(POLL)
            }
            // A transaction never spans a suspension, but a snapshot would end one: wait it out.
            while (transaction != null) delay(POLL)
            dirty = false
            firstUnsaved = null
            // Reads inside a transaction, or a sync round that found nothing new, wrote no rows: nothing to save.
            // The counter starts again at 0 when sql.js reopens the database for a snapshot.
            if (!force && totalChanges() == 0L) continue
            force = false
            val bytes = exportDatabase(db)
            storing++
            try {
                shelf.store(key, bytes)
            } catch (e: CancellationException) {
                // flush() takes over; a put already sent still lands first (IndexedDB keeps their order).
                dirty = true
                force = true
                throw e
            } catch (e: Throwable) {
                dirty = true
                force = true
                println("Benbow: saving $key failed, retrying: ${e.message}")
                delay(RETRY_MS)
            } finally {
                storing--
            }
        }
    }

    private fun totalChanges(): Long = single("SELECT total_changes()") { it.getLong(0) ?: 0 }

    private fun <T> single(sql: String, read: (SqlCursor) -> T): T = executeQuery(null, sql, { c ->
        check(c.next().value) { sql }
        QueryResult.Value(read(c))
    }, 0).value

    private fun inTransaction(block: () -> Unit) {
        val t = newTransaction().value as Transaction
        var ok = false
        try {
            block()
            ok = true
        } finally {
            t.endTransaction(ok)
        }
    }

    private class Binder : SqlPreparedStatement {
        val values = newArray()

        override fun bindBytes(index: Int, bytes: ByteArray?) =
            if (bytes == null) setNull(values, index) else setValue(values, index, bytes.toUint8Array())

        override fun bindLong(index: Int, long: Long?) = when {
            long == null -> setNull(values, index)
            long in -MAX_EXACT..MAX_EXACT -> setNumber(values, index, long.toDouble())
            else -> setBigInt(values, index, long)
        }

        override fun bindDouble(index: Int, double: Double?) = if (double ==
            null
        ) {
            setNull(values, index)
        } else {
            setNumber(values, index, double)
        }

        override fun bindString(index: Int, string: String?) = if (string ==
            null
        ) {
            setNull(values, index)
        } else {
            setString(values, index, string)
        }

        override fun bindBoolean(index: Int, boolean: Boolean?) =
            if (boolean == null) setNull(values, index) else setNumber(values, index, if (boolean) 1.0 else 0.0)
    }

    private class Cursor(private val statement: JsAny) : SqlCursor {
        private var row: JsAny? = null

        override fun next(): QueryResult<Boolean> {
            val more = step(statement)
            row = if (more) row(statement) else null
            return QueryResult.Value(more)
        }

        private inline fun <T> cell(index: Int, read: (JsAny, Int) -> T): T? {
            val row = checkNotNull(row) { "No current row" }
            return if (isNull(row, index)) null else read(row, index)
        }

        override fun getString(index: Int): String? = cell(index, ::cellString)

        override fun getLong(index: Int): Long? = cell(index, ::cellLong)

        override fun getBytes(index: Int): ByteArray? = cell(index) { row, i -> cellBytes(row, i).toByteArray() }

        override fun getDouble(index: Int): Double? = cell(index, ::cellDouble)

        override fun getBoolean(index: Int): Boolean? = cell(index) { row, i -> cellLong(row, i) != 0L }
    }

    companion object {
        private val QUIET = 1.seconds
        private val MAX_WAIT = 5.seconds
        private val POLL = 100.milliseconds
        private const val RETRY_MS = 5_000L
        private const val MAX_EXACT = (1L shl 53) - 1 // a JavaScript number holds it exactly
        private var sqlJs: JsAny? = null

        /**
         * Opens [name] from browser storage, creating or migrating it to [schema]. Before a migration, the old file is
         * kept beside it (data-safety S10). A damaged file is kept aside too, never deleted; an empty database then
         * fills again from the server.
         */
        suspend fun open(name: String, schema: SqlSchema<QueryResult.Value<Unit>>, shelf: FileShelf): BrowserSqlDriver {
            val sqlJs = sqlJs ?: initSqlJs().await<JsAny>().also { sqlJs = it }
            val now = Clock.System.now().toEpochMilliseconds()
            val stored = shelf.load(name)
            val driver = BrowserSqlDriver(shelf, name, newDatabase(sqlJs, stored))
            if (stored != null && !driver.intact()) {
                shelf.store("$name.damaged-$now", stored)
                println("Benbow: $name was damaged; kept as $name.damaged-$now, starting empty")
                closeDatabase(driver.db)
                driver.db = newDatabase(sqlJs, null)
            }
            val version = driver.single("PRAGMA user_version") { it.getLong(0) ?: 0 }
            when {
                version == 0L -> driver.inTransaction {
                    schema.create(driver)
                    driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
                }

                version < schema.version -> {
                    shelf.store("$name.before-v${schema.version}-$now", stored!!)
                    driver.inTransaction {
                        schema.migrate(driver, version, schema.version)
                        driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
                    }
                }

                version > schema.version -> error("$name is from a newer Benbow (schema $version); reload the page to update")
            }
            if (version < schema.version) {
                driver.force = true
                driver.dirty = true
                driver.flush()
            }
            return driver
        }
    }

    private fun intact(): Boolean = try {
        single("PRAGMA quick_check") { it.getString(0) } == "ok"
    } catch (e: Throwable) {
        false
    }
}
