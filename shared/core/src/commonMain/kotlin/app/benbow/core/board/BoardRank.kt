package app.benbow.core.board

/**
 * The board's order (ADR-040). Each note holds a string key; the board shows its notes by key, then by id. A move
 * writes the moved note's key only, one between its new neighbours' keys, so two devices never rewrite each other's
 * order (each key is an LWW field of its own note).
 *
 * The keys are rocicorp's fractional indexing (CC0): an integer part whose first character tells its length, then a
 * base-62 fraction without trailing zeros. Putting notes on top again and again only counts the integer down, so keys
 * stay two or three characters long for thousands of new notes.
 */
object BoardRank {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val ZERO = '0'
    private val SMALLEST = "A" + "0".repeat(26)

    /** The key of a board's first note. */
    const val FIRST = "a0"

    /** Whether [key] is one this scheme makes; any other value (missing, another app's) counts as no key. */
    fun isValid(key: String?): Boolean = key != null && runCatching { validate(key) }.isSuccess

    /** A key after [a] and before [b]; null is an open end. */
    fun between(a: String?, b: String?): String {
        a?.let(::validate)
        b?.let(::validate)
        require(a == null || b == null || a < b) { "$a >= $b" }
        if (a == null) {
            if (b == null) return FIRST
            val ib = integerPart(b)
            if (ib == SMALLEST) return ib + midpoint("", b.substring(ib.length))
            if (ib < b) return ib
            return decrement(ib) ?: error("no key before $b")
        }
        val ia = integerPart(a)
        val fa = a.substring(ia.length)
        if (b == null) return increment(ia) ?: (ia + midpoint(fa, null))
        val ib = integerPart(b)
        if (ia == ib) return ia + midpoint(fa, b.substring(ib.length))
        val next = increment(ia) ?: error("no key after $a")
        return if (next < b) next else ia + midpoint(fa, null)
    }

    /**
     * Notes in board order: those without a valid key first (newest id first, so new notes from elsewhere show on
     * top), then by key, then by id.
     */
    fun <T> sort(items: List<T>, id: (T) -> String, key: (T) -> String?): List<T> = items.sortedWith { x, y ->
        val kx = key(x)?.takeIf(::isValid)
        val ky = key(y)?.takeIf(::isValid)
        when {
            kx == null && ky == null -> id(y).compareTo(id(x))
            kx == null -> -1
            ky == null -> 1
            else -> kx.compareTo(ky).takeIf { it != 0 } ?: id(x).compareTo(id(y))
        }
    }

    /**
     * The keys to write so that [moving] shows at [at] in [order] (a section's notes as shown, top to bottom, without
     * [moving], each with its key). Mostly that is [moving]'s key alone; notes without a valid key get one first (in
     * the order they show), and when two devices put notes in the same gap their equal keys are pulled apart first.
     */
    fun place(moving: String, order: List<Pair<String, String?>>, at: Int): Map<String, String> {
        require(at in 0..order.size) { "at $at of ${order.size}" }
        val keys = order.map { (_, key) -> key?.takeIf(::isValid) }.toMutableList()
        val writes = LinkedHashMap<String, String>()
        // Notes without a key show on top: give them keys before the first that has one, keeping their order.
        val lastWithout = keys.indexOfLast { it == null }
        for (i in lastWithout downTo 0) {
            val key = between(null, keys.getOrNull(i + 1))
            keys[i] = key
            writes[order[i].first] = key
        }
        // Equal keys below the gap (and after it) move apart, each just after the one above it.
        val above = keys.getOrNull(at - 1)
        if (above != null) {
            var previous: String = above
            for (i in at until keys.size) {
                if (keys[i]!! > previous) break
                val floor = previous
                val fresh = between(floor, keys.drop(i + 1).firstOrNull { it!! > floor })
                keys[i] = fresh
                writes[order[i].first] = fresh
                previous = fresh
            }
        }
        writes[moving] = between(above, keys.getOrNull(at))
        return writes
    }

    private fun midpoint(a: String, b: String?): String {
        if (b != null) {
            var n = 0
            while (n < b.length && a.getOrElse(n) { ZERO } == b[n]) n++
            if (n > 0) return b.substring(0, n) + midpoint(a.drop(n), b.drop(n))
        }
        val digitA = if (a.isNotEmpty()) DIGITS.indexOf(a[0]) else 0
        val digitB = if (b != null) DIGITS.indexOf(b[0]) else DIGITS.length
        return when {
            digitB - digitA > 1 -> DIGITS[(digitA + digitB + 1) / 2].toString()
            b != null && b.length > 1 -> b.substring(0, 1)
            else -> DIGITS[digitA] + midpoint(a.drop(1), null)
        }
    }

    private fun integerLength(head: Char): Int = when (head) {
        in 'a'..'z' -> head - 'a' + 2
        in 'A'..'Z' -> 'Z' - head + 2
        else -> throw IllegalArgumentException("not a key: $head")
    }

    private fun integerPart(key: String): String {
        val length = integerLength(key[0])
        require(length <= key.length) { "not a key: $key" }
        return key.substring(0, length)
    }

    private fun validate(key: String) {
        require(key.isNotEmpty() && key != SMALLEST && key.all { it in DIGITS }) { "not a key: $key" }
        require(key.substring(integerPart(key).length).lastOrNull() != ZERO) { "not a key: $key" }
    }

    private fun increment(integer: String): String? {
        val head = integer[0]
        val digits = integer.substring(1).toCharArray()
        var carry = true
        var i = digits.lastIndex
        while (carry && i >= 0) {
            val d = DIGITS.indexOf(digits[i]) + 1
            if (d == DIGITS.length) {
                digits[i] = ZERO
            } else {
                digits[i] = DIGITS[d]
                carry = false
            }
            i--
        }
        if (!carry) return head + digits.concatToString()
        if (head == 'Z') return "a$ZERO"
        if (head == 'z') return null
        val next = head + 1
        return next + if (next > 'a') digits.concatToString() + ZERO else digits.concatToString().dropLast(1)
    }

    private fun decrement(integer: String): String? {
        val head = integer[0]
        val digits = integer.substring(1).toCharArray()
        var borrow = true
        var i = digits.lastIndex
        while (borrow && i >= 0) {
            val d = DIGITS.indexOf(digits[i]) - 1
            if (d == -1) {
                digits[i] = DIGITS.last()
            } else {
                digits[i] = DIGITS[d]
                borrow = false
            }
            i--
        }
        if (!borrow) return head + digits.concatToString()
        if (head == 'a') return "Z" + DIGITS.last()
        if (head == 'A') return null
        val previous = head - 1
        return previous + if (previous < 'Z') digits.concatToString() + DIGITS.last() else digits.concatToString().dropLast(1)
    }
}
