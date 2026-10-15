@file:OptIn(ExperimentalWasmJsInterop::class, UnsafeWasmMemoryApi::class)

package app.benbow.client

import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.WebAssembly
import kotlin.wasm.unsafe.wasmMemory
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// Bytes between Kotlin and JavaScript (ADR-029). A ByteArray lives in the wasm GC heap, a Uint8Array in JavaScript's;
// each copy goes through a 1 MB window of linear memory, which both sides read at native speed (no per-byte calls).

private const val WINDOW = 1 shl 20

/** A copy of this Uint8Array. */
fun JsAny.toByteArray(): ByteArray {
    val size = byteLength(this)
    val out = ByteArray(size)
    if (size == 0) return out
    withScopedMemoryAllocator { allocator ->
        val window = allocator.allocate(minOf(size, WINDOW))
        var at = 0
        while (at < size) {
            val n = minOf(WINDOW, size - at)
            intoMemory(wasmMemory, this, at, n, window.address.toInt())
            for (i in 0 until n) out[at + i] = (window + i).loadByte()
            at += n
        }
    }
    return out
}

/** A Uint8Array copy of these bytes. */
fun ByteArray.toUint8Array(): JsAny {
    val out = newBytes(size)
    if (isEmpty()) return out
    withScopedMemoryAllocator { allocator ->
        val window: Pointer = allocator.allocate(minOf(size, WINDOW))
        var at = 0
        while (at < size) {
            val n = minOf(WINDOW, size - at)
            for (i in 0 until n) (window + i).storeByte(this[at + i])
            fromMemory(wasmMemory, window.address.toInt(), n, out, at)
            at += n
        }
    }
    return out
}

// memory.buffer is read on every call: it is replaced whenever the memory grows.
private fun intoMemory(memory: WebAssembly.Memory, source: JsAny, from: Int, length: Int, to: Int): Unit =
    js("{ new Uint8Array(memory.buffer, to, length).set(source.subarray(from, from + length)); }")

private fun fromMemory(memory: WebAssembly.Memory, from: Int, length: Int, target: JsAny, at: Int): Unit =
    js("{ target.set(new Uint8Array(memory.buffer, from, length), at); }")
