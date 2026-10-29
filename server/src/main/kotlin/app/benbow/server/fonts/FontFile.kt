package app.benbow.server.fonts

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.roundToInt

/**
 * A font file as the server reads it (ADR-052): a TrueType or OpenType font, or a WOFF holding one (unpacked here, so
 * every device draws it as it is). Only what the server needs: its family's name, its weight, whether it is italic,
 * whether it has Russian and Latin letters, and its x-height, by which it is set against the app's font.
 */
internal class FontFile private constructor(
    /** The font as TrueType or OpenType bytes. */
    val bytes: ByteArray,
    val family: String,
    val weight: Int,
    val italic: Boolean,
    val cyrillic: Boolean,
    val latin: Boolean,
    /** Its x-height as a share of the em; 0 when the file doesn't say. */
    val xHeight: Float,
    val openType: Boolean,
) {
    /** How large notes set it (FontInfo.scale): its small letters about as tall as the app's font's, within reason. */
    val scale: Float get() = if (xHeight <= 0f) 1f else ((APP_X_HEIGHT / xHeight * 20).roundToInt() / 20f).coerceIn(0.9f, 1.4f)

    sealed interface Read {
        class Font(val font: FontFile) : Read

        /** Why the file isn't taken, said to the owner on the admin page. */
        class Refused(val reason: String) : Read
    }

    companion object {
        const val MAX_SIZE = 12 * 1024 * 1024

        /** Roboto's x-height in ems: the app's font on Android, near enough the others'. */
        private const val APP_X_HEIGHT = 0.528f

        private const val TRUE_TYPE = 0x00010000
        private const val OTTO = 0x4F54544F
        private const val TRUE = 0x74727565

        fun read(input: ByteArray): Read = try {
            parse(input)
        } catch (_: IndexOutOfBoundsException) {
            Read.Refused(BROKEN)
        } catch (_: BufferUnderflowException) {
            Read.Refused(BROKEN)
        } catch (_: DataFormatException) {
            Read.Refused(BROKEN)
        } catch (_: IllegalArgumentException) {
            Read.Refused(BROKEN)
        }

        private const val BROKEN = "файл повреждён или это не шрифт"

        private fun parse(input: ByteArray): Read {
            if (input.size > MAX_SIZE) return Read.Refused("файл больше 12 МБ: это не похоже на один шрифт")
            if (input.size < 12) return Read.Refused("это не шрифт")
            val bytes = when (String(input, 0, 4, Charsets.ISO_8859_1)) {
                "wOFF" -> unwoff(input)

                "wOF2" -> return Read.Refused(
                    "WOFF2 не подходит: возьмите тот же шрифт в TTF или OTF (так его отдают Google Fonts и другие сайты)",
                )

                "ttcf" -> return Read.Refused("это коллекция шрифтов (.ttc): нужен один шрифт, TTF или OTF")

                else -> input
            }
            val b = ByteBuffer.wrap(bytes)
            val version = b.getInt(0)
            if (version != TRUE_TYPE && version != OTTO && version != TRUE) return Read.Refused("это не шрифт TTF или OTF")
            val tables = tables(b)
            for (required in listOf("cmap", "head", "name")) {
                if (required !in tables) return Read.Refused("в шрифте нет таблицы «$required»: он неполный")
            }
            if ("glyf" !in tables && "CFF " !in tables && "CFF2" !in tables) return Read.Refused("в шрифте нет самих букв")
            val head = tables.getValue("head")
            val unitsPerEm = u16(b, head.offset + 18).takeIf { it in 16..16_384 } ?: return Read.Refused(BROKEN)
            val macStyle = u16(b, head.offset + 44)
            val os2 = tables["OS/2"]
            val weight = os2?.let { u16(b, it.offset + 4) }?.takeIf { it in 1..1000 } ?: if (macStyle and 1 != 0) 700 else 400
            val italic = os2?.takeIf { it.length >= 64 }?.let { u16(b, it.offset + 62) and 1 != 0 } ?: (macStyle and 2 != 0)
            val xHeight = os2?.takeIf { u16(b, it.offset) >= 2 && it.length >= 88 }?.let { b.getShort(it.offset + 86).toInt() }
                ?.takeIf { it > 0 }?.let { it.toFloat() / unitsPerEm } ?: 0f
            val family = family(b, tables.getValue("name"))?.trim()?.takeIf { it.isNotEmpty() }
                ?: return Read.Refused("у шрифта нет имени")
            val glyph = glyphs(b, tables.getValue("cmap")) ?: return Read.Refused("в шрифте не найти, где какая буква")
            val cyrillic = (0x0410..0x044F).count { glyph(it) != 0 } >= 60
            val latin = ('a'..'z').all { glyph(it.code) != 0 } && ('A'..'Z').all { glyph(it.code) != 0 }
            if (!cyrillic && !latin) return Read.Refused("в шрифте нет ни русских, ни латинских букв")
            return Read.Font(FontFile(bytes, family, weight, italic, cyrillic, latin, xHeight, openType = version == OTTO))
        }

        private class Table(val offset: Int, val length: Int)

        private fun tables(b: ByteBuffer): Map<String, Table> {
            val count = u16(b, 4)
            require(count in 1..200)
            return (0 until count).associate { i ->
                val record = 12 + i * 16
                val tag = ByteArray(4).also { b.get(record, it) }.toString(Charsets.ISO_8859_1)
                val offset = u32(b, record + 8)
                val length = u32(b, record + 12)
                require(offset >= 0 && length >= 0 && offset.toLong() + length <= b.capacity())
                tag to Table(offset.toInt(), length.toInt())
            }
        }

        /** The family's name: the typographic one (16) before the plain one (1), Russian before English. */
        private fun family(b: ByteBuffer, t: Table): String? {
            val count = u16(b, t.offset + 2)
            val strings = t.offset + u16(b, t.offset + 4)
            class Name(val platform: Int, val encoding: Int, val language: Int, val id: Int, val length: Int, val at: Int)
            val names = (0 until count).map { i ->
                val r = t.offset + 6 + i * 12
                Name(u16(b, r), u16(b, r + 2), u16(b, r + 4), u16(b, r + 6), u16(b, r + 8), strings + u16(b, r + 10))
            }

            fun rank(n: Name) = when {
                n.platform == 3 && n.language == 0x0419 -> 0
                n.platform == 3 && n.language == 0x0409 -> 1
                n.platform == 3 -> 2
                n.platform == 0 -> 3
                n.platform == 1 && n.encoding == 0 -> 4
                else -> 99
            }
            for (id in listOf(16, 1)) {
                val name = names.filter { it.id == id && rank(it) < 99 }.minByOrNull(::rank) ?: continue
                val raw = ByteArray(name.length).also { b.get(name.at, it) }
                return String(raw, if (name.platform == 1) Charsets.ISO_8859_1 else Charsets.UTF_16BE)
            }
            return null
        }

        /** Code point to glyph through the best Unicode cmap the font has (format 12 or 4); null when it has none. */
        private fun glyphs(b: ByteBuffer, t: Table): ((Int) -> Int)? {
            val count = u16(b, t.offset + 2)
            val subtables = (0 until count).map { i ->
                val r = t.offset + 4 + i * 8
                Triple(u16(b, r), u16(b, r + 2), t.offset + u32(b, r + 4))
            }
            for ((platform, encoding) in listOf(3 to 10, 0 to 4, 0 to 6, 3 to 1, 0 to 3, 0 to 2, 0 to 1, 0 to 0)) {
                val at = subtables.firstOrNull { it.first == platform && it.second == encoding }?.third ?: continue
                when (u16(b, at)) {
                    12 -> return format12(b, at)
                    4 -> return format4(b, at)
                }
            }
            return null
        }

        private fun format4(b: ByteBuffer, at: Int): (Int) -> Int {
            val segX2 = u16(b, at + 6)
            val ends = at + 14
            val starts = ends + segX2 + 2
            val deltas = starts + segX2
            val ranges = deltas + segX2
            return lookup@{ c ->
                for (i in 0 until segX2 / 2) {
                    if (c > u16(b, ends + 2 * i)) continue
                    val start = u16(b, starts + 2 * i)
                    if (c < start) return@lookup 0
                    val delta = b.getShort(deltas + 2 * i).toInt()
                    val rangeAt = ranges + 2 * i
                    val range = u16(b, rangeAt)
                    if (range == 0) return@lookup (c + delta) and 0xFFFF
                    val g = u16(b, rangeAt + range + 2 * (c - start))
                    return@lookup if (g == 0) 0 else (g + delta) and 0xFFFF
                }
                0
            }
        }

        private fun format12(b: ByteBuffer, at: Int): (Int) -> Int {
            val groups = u32(b, at + 12)
            require(groups in 0..100_000)
            return lookup@{ c ->
                for (i in 0 until groups) {
                    val g = at + 16 + i * 12
                    val start = u32(b, g)
                    if (c < start) return@lookup 0
                    if (c <= u32(b, g + 4)) return@lookup u32(b, g + 8) + (c - start)
                }
                0
            }
        }

        /** A WOFF (1.0) back into the font it holds: each table inflated where it was packed, offsets laid out anew. */
        private fun unwoff(w: ByteArray): ByteArray {
            val b = ByteBuffer.wrap(w)
            val flavor = b.getInt(4)
            val count = u16(b, 12)
            require(count in 1..200)
            class Entry(val tag: Int, val checksum: Int, val data: ByteArray)
            var total = 0L
            val entries = (0 until count).map { i ->
                val r = 44 + i * 20
                val offset = u32(b, r + 4)
                val packed = u32(b, r + 8)
                val size = u32(b, r + 12)
                total += size
                require(size in 0..MAX_SIZE && total <= 4L * MAX_SIZE && offset.toLong() + packed <= w.size)
                val data = if (packed < size) {
                    val inflater = Inflater().apply { setInput(w, offset, packed) }
                    ByteArray(size).also {
                        val n = inflater.inflate(it)
                        inflater.end()
                        require(n == size)
                    }
                } else {
                    w.copyOfRange(offset, offset + size)
                }
                Entry(b.getInt(r), b.getInt(r + 16), data)
            }
            var power = 1
            var log = 0
            while (power * 2 <= count) {
                power *= 2
                log++
            }
            val header = 12 + 16 * count
            val out = ByteBuffer.allocate(header + entries.sumOf { (it.data.size + 3) and 3.inv() })
            out.putInt(flavor).putShort(count.toShort()).putShort((power * 16).toShort()).putShort(log.toShort())
                .putShort((count * 16 - power * 16).toShort())
            var offset = header
            for (e in entries) {
                out.putInt(e.tag).putInt(e.checksum).putInt(offset).putInt(e.data.size)
                offset += (e.data.size + 3) and 3.inv()
            }
            for (e in entries) {
                out.put(e.data)
                repeat(((e.data.size + 3) and 3.inv()) - e.data.size) { out.put(0) }
            }
            return out.array()
        }

        private fun u16(b: ByteBuffer, at: Int) = b.getShort(at).toInt() and 0xFFFF

        private fun u32(b: ByteBuffer, at: Int): Int = b.getInt(at).also { require(it >= 0) }
    }
}
