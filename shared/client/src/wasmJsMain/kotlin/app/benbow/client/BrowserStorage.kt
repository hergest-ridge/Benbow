@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.client

import kotlinx.coroutines.await
import kotlin.js.Promise

/** Where whole database files wait between visits (ADR-029). */
interface FileShelf {
    /** The stored file (a Uint8Array), or null. */
    suspend fun load(key: String): JsAny?

    /** Stores [bytes] (a Uint8Array) under [key], replacing the previous file only once the new one is complete. */
    suspend fun store(key: String, bytes: JsAny)
}

/** The app's data on this browser belongs to another tab. */
class OpenElsewhereException : Exception("Benbow is open in another tab")

/**
 * This browser's IndexedDB for the page's origin. A put replaces one value atomically and is flushed to disk before it
 * reports done, so a file there is always a complete snapshot, the previous one until the next put has committed.
 */
class BrowserShelf private constructor() : FileShelf {
    override suspend fun load(key: String): JsAny? = idbGet(key).await()

    override suspend fun store(key: String, bytes: JsAny) {
        idbPut(key, bytes).await<JsAny?>()
    }

    companion object {
        /**
         * Takes this browser's data for this tab. A second tab would keep its own copy of the database and overwrite
         * the first one's saves, so it gets [OpenElsewhereException] instead.
         */
        suspend fun claim(): BrowserShelf {
            initStorage()
            if (!claimTab().await().toBoolean()) throw OpenElsewhereException()
            return BrowserShelf()
        }
    }
}

private fun idbGet(key: String): Promise<JsAny?> = js(
    """globalThis.benbowFiles().then(db => new Promise((resolve, reject) => {
        const request = db.transaction('files').objectStore('files').get(key);
        request.onsuccess = () => resolve(request.result ?? null);
        request.onerror = () => reject(request.error);
    }))""",
)

private fun idbPut(key: String, bytes: JsAny): Promise<JsAny?> = js(
    """globalThis.benbowFiles().then(db => new Promise((resolve, reject) => {
        const tx = db.transaction('files', 'readwrite', { durability: 'strict' });
        tx.objectStore('files').put(bytes, key);
        tx.oncomplete = () => resolve(null);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
    }))""",
)

/** Opens the database once per tab; asks the browser to keep this origin's storage (not evict it under pressure). */
private fun initStorage(): Unit = js(
    """{
        let opened = null;
        globalThis.benbowFiles = () => opened ??= new Promise((resolve, reject) => {
            const request = indexedDB.open('benbow', 1);
            request.onupgradeneeded = () => request.result.createObjectStore('files');
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
        navigator.storage?.persist?.();
    }""",
)

private fun claimTab(): Promise<JsBoolean> = js(
    """new Promise(resolve => {
        if (navigator.locks) {
            navigator.locks.request('benbow-data', { ifAvailable: true }, lock => {
                resolve(lock != null);
                return lock ? new Promise(() => {}) : null; // held while the tab lives
            });
            return;
        }
        // No Web Locks (an insecure context): ask the other tabs instead.
        const channel = new BroadcastChannel('benbow-data');
        let taken = false;
        channel.onmessage = e => { if (e.data === 'me') taken = true; };
        channel.postMessage('who');
        setTimeout(() => {
            if (taken) { channel.close(); resolve(false); return; }
            channel.onmessage = e => { if (e.data === 'who') channel.postMessage('me'); };
            resolve(true);
        }, 300);
    })""",
)
