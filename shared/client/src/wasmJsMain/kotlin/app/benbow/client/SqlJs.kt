@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.client

import kotlin.js.Promise

// sql.js: SQLite compiled to WebAssembly, synchronous, one database file in the tab's memory (ADR-029). It has FTS4
// and JSON, which the journal's schema needs (the official sqlite-wasm build has no FTS4). These are the few calls
// the driver makes; values cross as plain JavaScript values.

@JsModule("sql.js")
internal external fun initSqlJs(): Promise<JsAny>

internal fun newDatabase(sqlJs: JsAny, bytes: JsAny?): JsAny = js("new sqlJs.Database(bytes)")

/** The whole database file. sql.js closes and reopens the database to read it: no statement may be open. */
internal fun exportDatabase(db: JsAny): JsAny = js("db.export()")

internal fun rowsModified(db: JsAny): Int = js("db.getRowsModified()")

internal fun closeDatabase(db: JsAny): Unit = js("{ db.close(); }")

internal fun prepare(db: JsAny, sql: String): JsAny = js("db.prepare(sql)")

internal fun bind(statement: JsAny, values: JsAny): Unit = js("{ statement.bind(values); }")

internal fun step(statement: JsAny): Boolean = js("statement.step()")

internal fun free(statement: JsAny): Unit = js("{ statement.free(); }")

/** The current row; integers come as BigInt, so 64-bit values stay exact. */
internal fun row(statement: JsAny): JsAny = js("statement.get(null, { useBigInt: true })")

internal fun newArray(): JsAny = js("[]")

internal fun setNull(array: JsAny, index: Int): Unit = js("{ array[index] = null; }")

internal fun setString(array: JsAny, index: Int, value: String): Unit = js("{ array[index] = value; }")

internal fun setNumber(array: JsAny, index: Int, value: Double): Unit = js("{ array[index] = value; }")

/** A Long arrives as BigInt; sql.js binds it as text, which INTEGER columns store as the exact integer. */
internal fun setBigInt(array: JsAny, index: Int, value: Long): Unit = js("{ array[index] = value; }")

internal fun setValue(array: JsAny, index: Int, value: JsAny): Unit = js("{ array[index] = value; }")

internal fun isNull(row: JsAny, index: Int): Boolean = js("row[index] == null")

internal fun cellString(row: JsAny, index: Int): String = js("String(row[index])")

internal fun cellDouble(row: JsAny, index: Int): Double = js("Number(row[index])")

internal fun cellLong(row: JsAny, index: Int): Long = js(
    "typeof row[index] === 'bigint' ? row[index] : BigInt(Math.trunc(Number(row[index])))",
)

internal fun cellBytes(row: JsAny, index: Int): JsAny = js("row[index]")

internal fun byteLength(array: JsAny): Int = js("array.length")

internal fun byteAt(array: JsAny, index: Int): Int = js("array[index]")

internal fun newBytes(size: Int): JsAny = js("new Uint8Array(size)")

internal fun setByte(array: JsAny, index: Int, value: Int): Unit = js("{ array[index] = value; }")

internal fun ByteArray.toUint8Array(): JsAny = newBytes(size).also { array -> forEachIndexed { i, b -> setByte(array, i, b.toInt()) } }

internal fun JsAny.toByteArray(): ByteArray = ByteArray(byteLength(this)) { byteAt(this, it).toByte() }
