package app.benbow.core.audio

/**
 * How tag text is spelled. Formats declare an encoding, but Russian files often lie: text labelled ISO-8859-1 (ID3's
 * encoding 0, ID3v1, RIFF INFO) is really Windows-1251 or UTF-8, and some taggers put those bytes one per UTF-16 unit.
 * Text that comes out as single bytes is looked at again ([repair]); real Latin-1 (Beyoncé, Mötley Crüe) stays.
 */
internal object TagText {
    fun utf8(bytes: ByteArray, from: Int, to: Int): String? = clean(repair(bytes.decodeToString(from, to)))

    /** UTF-16 with a byte-order mark, or big-endian without one (ID3's encoding 2). */
    fun utf16(bytes: ByteArray, from: Int, to: Int, bigEndian: Boolean = false): String? {
        var start = from
        var big = bigEndian
        if (to - start >= 2) {
            val mark = bytes.u16be(start)
            if (mark == 0xFEFF || mark == 0xFFFE) {
                big = mark == 0xFEFF
                start += 2
            }
        }
        val chars = CharArray((to - start) / 2) { k ->
            val i = start + k * 2
            Char(if (big) bytes.u16be(i) else bytes.u16le(i))
        }
        return clean(repair(chars.concatToString()))
    }

    /** Bytes said to be ISO-8859-1. */
    fun legacy(bytes: ByteArray, from: Int, to: Int): String? = clean(single(bytes.copyOfRange(from, to)))

    /** Text whose characters are all single bytes, read again as those bytes. */
    fun repair(text: String): String {
        if (text.none { it.code in 0x80..0xFF } || text.any { it.code > 0xFF }) return text
        return single(ByteArray(text.length) { text[it].code.toByte() })
    }

    fun clean(text: String): String? = text.trim { it.isWhitespace() || it.code == 0 }.take(MAX_LENGTH).takeIf { it.isNotEmpty() }

    private fun single(bytes: ByteArray): String = when {
        bytes.all { it >= 0 } -> bytes.decodeToString()
        isUtf8(bytes) -> bytes.decodeToString()
        looksCyrillic(bytes) -> buildString(bytes.size) { bytes.forEach { b -> append(cp1251(b.toInt() and 0xFF)) } }
        else -> buildString(bytes.size) { bytes.forEach { b -> append(Char(b.toInt() and 0xFF)) } }
    }

    /** Valid UTF-8 with at least one character past ASCII. */
    private fun isUtf8(bytes: ByteArray): Boolean {
        var i = 0
        var wide = false
        while (i < bytes.size) {
            val b = bytes.u8(i)
            val extra = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + extra >= bytes.size && extra > 0) return false
            for (k in 1..extra) if (bytes.u8(i + k) and 0xC0 != 0x80) return false
            if (extra > 0) wide = true
            i += extra + 1
        }
        return wide
    }

    /**
     * Windows-1251 Russian: nearly every high byte is a Cyrillic letter, and they come in runs (words), where Latin-1's
     * accented letters stand alone between ASCII ones.
     */
    private fun looksCyrillic(bytes: ByteArray): Boolean {
        var high = 0
        var letters = 0
        var ascii = 0
        var run = 0
        var longest = 0
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            if (b >= 0x80) {
                high++
                if (isCyrillicLetter(b)) {
                    letters++
                    run++
                    longest = maxOf(longest, run)
                } else {
                    run = 0
                }
            } else {
                run = 0
                if (Char(b).isLetter()) ascii++
            }
        }
        return letters * 10 >= high * 8 && (longest >= 3 || letters * 2 >= letters + ascii)
    }

    private fun isCyrillicLetter(b: Int): Boolean = b >= 0xC0 || b in CP1251_LETTERS

    private fun cp1251(b: Int): Char = if (b < 0x80) Char(b) else Char(CP1251[b - 0x80])

    /** Ё ё, and the Ukrainian and Belarusian letters. */
    private val CP1251_LETTERS = setOf(0xA8, 0xB8, 0xAA, 0xBA, 0xAF, 0xBF, 0xB2, 0xB3, 0xA1, 0xA2, 0xA5, 0xB4, 0xBD, 0xBE, 0xA3, 0xBC)

    private const val MAX_LENGTH = 300

    /** Windows-1251's upper half, as the JDK decodes it (0x98 is unassigned). */
    private val CP1251 = intArrayOf(
        0x0402, 0x0403, 0x201A, 0x0453, 0x201E, 0x2026, 0x2020, 0x2021, 0x20AC, 0x2030, 0x0409, 0x2039, 0x040A, 0x040C, 0x040B, 0x040F,
        0x0452, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0xFFFD, 0x2122, 0x0459, 0x203A, 0x045A, 0x045C, 0x045B, 0x045F,
        0x00A0, 0x040E, 0x045E, 0x0408, 0x00A4, 0x0490, 0x00A6, 0x00A7, 0x0401, 0x00A9, 0x0404, 0x00AB, 0x00AC, 0x00AD, 0x00AE, 0x0407,
        0x00B0, 0x00B1, 0x0406, 0x0456, 0x0491, 0x00B5, 0x00B6, 0x00B7, 0x0451, 0x2116, 0x0454, 0x00BB, 0x0458, 0x0405, 0x0455, 0x0457,
        0x0410, 0x0411, 0x0412, 0x0413, 0x0414, 0x0415, 0x0416, 0x0417, 0x0418, 0x0419, 0x041A, 0x041B, 0x041C, 0x041D, 0x041E, 0x041F,
        0x0420, 0x0421, 0x0422, 0x0423, 0x0424, 0x0425, 0x0426, 0x0427, 0x0428, 0x0429, 0x042A, 0x042B, 0x042C, 0x042D, 0x042E, 0x042F,
        0x0430, 0x0431, 0x0432, 0x0433, 0x0434, 0x0435, 0x0436, 0x0437, 0x0438, 0x0439, 0x043A, 0x043B, 0x043C, 0x043D, 0x043E, 0x043F,
        0x0440, 0x0441, 0x0442, 0x0443, 0x0444, 0x0445, 0x0446, 0x0447, 0x0448, 0x0449, 0x044A, 0x044B, 0x044C, 0x044D, 0x044E, 0x044F,
    )
}
