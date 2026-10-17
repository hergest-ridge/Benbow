package app.benbow.core.audio

import app.benbow.core.audio.Id3Files.apic
import app.benbow.core.audio.Id3Files.frame
import app.benbow.core.audio.Id3Files.tag
import app.benbow.core.audio.Id3Files.text
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackReaderTest {
    private suspend fun read(bytes: ByteArray) = TrackReader.read(BytesRead(bytes))

    @Test
    fun id3v23InWindows1251IsReadAsRussian() = runTest {
        val file = join(
            tag(
                3,
                frame(3, "TIT2", text(0, cp1251("Группа крови"))),
                frame(3, "TPE1", text(0, cp1251("Кино"))),
                frame(3, "TALB", text(0, cp1251("Группа крови"))),
            ),
            MpegFiles.frames(10),
        )
        val info = read(file)
        assertEquals(TrackTags("Группа крови", "Кино", "Группа крови"), info.tags)
        assertEquals(10L * MpegFiles.FRAME * 8 / 128, info.durationMs, "constant bitrate: size over bitrate")
    }

    @Test
    fun realLatin1StaysLatin1() = runTest {
        val info =
            read(
                join(
                    tag(3, frame(3, "TIT2", text(0, latin1("Déjà vu"))), frame(3, "TPE1", text(0, latin1("Beyoncé")))),
                    MpegFiles.frames(3),
                ),
            )
        assertEquals("Déjà vu", info.tags.title)
        assertEquals("Beyoncé", info.tags.artist)
    }

    @Test
    fun utf8LabelledLatin1IsUtf8() = runTest {
        assertEquals("Ария", read(join(tag(3, frame(3, "TPE1", text(0, utf8("Ария")))), MpegFiles.frames(3))).tags.artist)
    }

    @Test
    fun utf16WithByteOrderMarkAndCp1251SmuggledIntoUtf16() = runTest {
        val proper = read(join(tag(3, frame(3, "TIT2", text(1, utf16le("Восьмиклассница") + byteArrayOf(0, 0)))), MpegFiles.frames(3)))
        assertEquals("Восьмиклассница", proper.tags.title)
        // A tagger that wrote each cp1251 byte as a UTF-16 unit: "Ãðàæäàíñêàÿ îáîðîíà".
        val smuggled = cp1251("Гражданская оборона").let { bytes ->
            CharArray(bytes.size) { Char(bytes[it].toInt() and 0xFF) }.concatToString()
        }
        val broken = read(join(tag(3, frame(3, "TPE1", text(1, utf16le(smuggled)))), MpegFiles.frames(3)))
        assertEquals("Гражданская оборона", broken.tags.artist)
    }

    @Test
    fun id3v24Utf8SeveralArtistsAndTheFrontCover() = runTest {
        val other = picture(1)
        val front = picture(2)
        val file = join(
            tag(
                4,
                frame(4, "TIT2", text(3, utf8("Мама, мы все сошли с ума"))),
                frame(4, "TPE1", text(3, utf8("Кино") + 0.toByte() + utf8("Виктор Цой"))),
                frame(4, "APIC", apic(0, other)),
                frame(4, "APIC", apic(3, front, "обложка")),
            ),
            MpegFiles.xing(1000),
        )
        val info = read(file)
        assertEquals("Мама, мы все сошли с ума", info.tags.title)
        assertEquals("Кино, Виктор Цой", info.tags.artist)
        assertContentEquals(front, info.cover)
        assertEquals(1000L * 1152 * 1000 / 44_100, info.durationMs, "variable bitrate: frames from the Xing header")
    }

    @Test
    fun id3v24WithItunesPlainFrameSizes() = runTest {
        // 200 bytes: plain 0x000000C8 reads as syncsafe 0x48 = 72, which lands mid-frame.
        val long = "А".repeat(199)
        val file = join(
            tag(4, frame(4, "TIT2", text(0, cp1251(long)), plainSize = true), frame(4, "TPE1", text(0, ascii("Artist")), plainSize = true)),
            MpegFiles.frames(3),
        )
        val info = read(file)
        assertEquals(long, info.tags.title)
        assertEquals("Artist", info.tags.artist)
    }

    @Test
    fun id3v22AndAlbumArtistStandsInForArtist() = runTest {
        val info = read(join(tag(2, frame(2, "TT2", text(0, ascii("Song"))), frame(2, "TP2", text(0, ascii("Band")))), MpegFiles.frames(3)))
        assertEquals(TrackTags("Song", "Band"), info.tags)
    }

    @Test
    fun id3v1FillsWhatIsMissing() = runTest {
        val file =
            join(
                tag(3, frame(3, "TIT2", text(0, ascii("From v2")))),
                MpegFiles.frames(5),
                Id3Files.v1(ascii("From v1"), cp1251("Ленинград"), cp1251("Пуля")),
            )
        val info = read(file)
        assertEquals(TrackTags("From v2", "Ленинград", "Пуля"), info.tags)
        assertEquals(5L * MpegFiles.FRAME * 8 / 128, info.durationMs, "the ID3v1 tag isn't audio")
    }

    @Test
    fun mp4WithTheMovieBoxAtTheEnd() = runTest {
        val cover = picture(9)
        val info = read(Mp4Files.song("Звезда по имени Солнце", "Кино", cover, 225))
        assertEquals(TrackTags("Звезда по имени Солнце", "Кино"), info.tags)
        assertEquals(225_000L, info.durationMs)
        assertContentEquals(cover, info.cover)
    }

    @Test
    fun flacCommentsPictureAndLength() = runTest {
        val cover = picture(4)
        val file = VorbisFiles.flac(
            44_100,
            441_000,
            VorbisFiles.comments("title=Спокойная ночь", "ARTIST=Кино", "ARTIST=Виктор Цой", "ALBUM=Группа крови"),
            VorbisFiles.flacPicture(3, cover),
        )
        val info = read(file)
        assertEquals(TrackTags("Спокойная ночь", "Кино, Виктор Цой", "Группа крови"), info.tags)
        assertEquals(10_000L, info.durationMs)
        assertContentEquals(cover, info.cover)
    }

    @Test
    fun opusTagsOverTwoPagesAndTheLastGranule() = runTest {
        val info = read(VorbisFiles.opus(5, "TITLE=Кукушка", "ARTIST=Кино"))
        assertEquals(TrackTags("Кукушка", "Кино"), info.tags)
        assertEquals(5_000L, info.durationMs)
    }

    @Test
    fun waveInfoChunks() = runTest {
        val info = read(RiffFiles.wave(2, cp1251("Запись"), ascii("Recorder")))
        assertEquals(TrackTags("Запись", "Recorder"), info.tags)
        assertEquals(2_000L, info.durationMs)
    }

    @Test
    fun aiffCommonChunk() = runTest {
        val info = read(RiffFiles.aiff(88_200, "Take"))
        assertEquals("Take", info.tags.title)
        assertEquals(2_000L, info.durationMs)
    }

    @Test
    fun unsynchronisedId3v23() = runTest {
        // 0xFF bytes in a cover get a zero after them in an unsynchronised tag.
        val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val stuffed = byteArrayOf(0xFF.toByte(), 0, 0xD8.toByte(), 0xFF.toByte(), 0, 0xE0.toByte())
        val apic = join(byteArrayOf(0), ascii("image/jpeg"), byteArrayOf(0, 3, 0), stuffed)
        // ID3v2.3 unsynchronises the finished tag: frame sizes count the bytes before the zeros went in.
        val frameBytes = join(ascii("APIC"), be32(apic.size - 2), byteArrayOf(0, 0), apic)
        val info = read(join(tag(3, frameBytes, flags = 0x80), MpegFiles.frames(3)))
        assertContentEquals(cover, info.cover)
    }

    @Test
    fun brokenAndForeignFilesGiveNothingAndNeverThrow() = runTest {
        assertTrue(read(ByteArray(0)).tags.isEmpty)
        assertTrue(read(ascii("not a sound at all, just some text")).tags.isEmpty)
        val cut = Mp4Files.song("A", "B", picture(1), 10).let { it.copyOf(it.size - 40) }
        read(cut) // whatever it finds, it returns
        val tagCut = tag(3, frame(3, "TIT2", text(0, ascii("Title")))).copyOf(14)
        assertNull(read(tagCut).tags.title)
        for (n in listOf(5, 20, 60, 200)) read(VorbisFiles.opus(5, "TITLE=x").copyOf(n))
        for (n in listOf(
            5,
            30,
            70,
        )) {
            read(VorbisFiles.flac(44_100, 1, VorbisFiles.comments("TITLE=x"), VorbisFiles.flacPicture(3, picture(1))).copyOf(n))
        }
    }

    @Test
    fun theFieldIsCompactJsonAndRoundTrips() {
        val tags = TrackTags("Кукушка", "Кино")
        assertEquals("""{"title":"Кукушка","artist":"Кино"}""", tags.encode())
        assertEquals(tags, TrackTags.decode(tags.encode()))
        assertNull(TrackTags.decode("{}"))
        assertNull(TrackTags.decode("not json"))
        val fields = mapOf("track:a1" to tags.encode(), "transcript:a1" to "words", "track:a2" to null)
        assertEquals(mapOf("a1" to tags), TrackTags.of(fields))
    }
}
