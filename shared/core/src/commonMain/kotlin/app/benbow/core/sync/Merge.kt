package app.benbow.core.sync

import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException

/**
 * Three-way merge of lists: changes from both sides are combined; a chunk changed differently on
 * both sides is a conflict (null). Classic diff3 over LCS matches against the common base.
 */
fun <T> diff3(base: List<T>, ours: List<T>, theirs: List<T>): List<T>? {
    val toOurs = lcsMatches(base, ours)
    val toTheirs = lcsMatches(base, theirs)
    val result = ArrayList<T>()
    var b = 0
    var o = 0
    var t = 0
    while (true) {
        val stable = (b until base.size).firstOrNull { k ->
            val mo = toOurs[k]
            val mt = toTheirs[k]
            mo != null && mt != null && mo >= o && mt >= t
        }
        val bEnd = stable ?: base.size
        val oEnd = stable?.let { toOurs.getValue(it) } ?: ours.size
        val tEnd = stable?.let { toTheirs.getValue(it) } ?: theirs.size
        result += resolve(base.subList(b, bEnd), ours.subList(o, oEnd), theirs.subList(t, tEnd)) ?: return null
        if (stable == null) return result
        result += base[stable]
        b = stable + 1
        o = oEnd + 1
        t = tEnd + 1
    }
}

private fun <T> resolve(base: List<T>, ours: List<T>, theirs: List<T>): List<T>? = when {
    ours == theirs -> ours
    ours == base -> theirs
    theirs == base -> ours
    else -> null
}

/** Index map base → other for one longest common subsequence. */
private fun <T> lcsMatches(a: List<T>, b: List<T>): Map<Int, Int> {
    val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            lengths[i][j] = if (a[i] == b[j]) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
    }
    val matches = HashMap<Int, Int>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        when {
            a[i] == b[j] -> matches[i++] = j++
            lengths[i + 1][j] >= lengths[i][j + 1] -> i++
            else -> j++
        }
    }
    return matches
}

/**
 * Merges canonical document bodies block by block (one line per block, content-format.md).
 * Returns the merged canonical body, or null when the same block changed on both sides or the
 * result would be invalid (e.g. duplicate block keys). A null result is a conflict: keep both heads.
 */
fun mergeDocumentBodies(base: String, ours: String, theirs: String): String? {
    val merged = diff3(blockLines(base) ?: return null, blockLines(ours) ?: return null, blockLines(theirs) ?: return null)
        ?: return null
    val text = if (merged.isEmpty()) "{\"v\":${Document.CURRENT_VERSION},\"blocks\":[]}" else "{\"v\":${Document.CURRENT_VERSION},\"blocks\":[\n${merged.joinToString(",\n")}\n]}"
    val document = try {
        DocumentCodec.decode(text)
    } catch (_: DocumentFormatException) {
        return null
    }
    if (document.blocks.map { it.key }.toSet().size != document.blocks.size) return null
    return DocumentCodec.encode(document)
}

/** Canonical block lines without separators; null for documents that can't be merged (newer format). */
private fun blockLines(body: String): List<String>? {
    val document = try {
        DocumentCodec.decode(body)
    } catch (_: DocumentFormatException) {
        return null
    }
    if (!document.isEditable) return null
    if (document.blocks.isEmpty()) return emptyList()
    return DocumentCodec.encode(document).lines().drop(1).dropLast(1).map { it.removeSuffix(",") }
}
