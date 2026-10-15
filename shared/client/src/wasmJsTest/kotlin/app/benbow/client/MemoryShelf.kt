package app.benbow.client

/** IndexedDB stand-in for the tests, which run in Node. */
internal class MemoryShelf : FileShelf {
    val files = mutableMapOf<String, JsAny>()

    override suspend fun load(key: String): JsAny? = files[key]

    override suspend fun store(key: String, bytes: JsAny) {
        files[key] = bytes
    }

    override suspend fun delete(key: String) {
        files.remove(key)
    }
}
