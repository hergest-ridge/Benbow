package app.benbow.android

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import app.benbow.client.JournalRepository
import app.benbow.client.MediaFile
import app.benbow.core.content.AttachmentBlock
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.random.Random

/**
 * Benchmark builds only (app.benbow.bench, its own DB): fills an empty journal with half a year of made-up entries,
 * drawn pictures, comments, reactions and tags, so startup and scrolling are measured on a lived-in feed. Runs
 * synchronously so `am start -W` returns when it's done; does nothing when the journal already has entries.
 */
class BenchSeedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val services = (application as BenbowApplication).services
        runBlocking {
            if (services.repo.journal().first().isEmpty()) {
                seed(services.repo) { bytes, name ->
                    services.media.import(bytes.inputStream(), "image/jpeg", name, null)
                }
            }
        }
        finish()
    }

    private suspend fun seed(repo: JournalRepository, import: suspend (ByteArray, String) -> MediaFile) {
        val random = Random(7)
        val today = LocalDate.now()
        repeat(ENTRIES) { i ->
            val day = today.minusDays(i / 2L).toString()
            val lines = List(1 + random.nextInt(6)) { PHRASES[random.nextInt(PHRASES.size)] }
            val id = repo.createNote(day, TITLES[i % TITLES.size] + "\n" + lines.joinToString("\n"))
            if (i % 5 == 0) {
                val pictures = List(1 + random.nextInt(3)) { n ->
                    val (w, h) = SHAPES[(i + n) % SHAPES.size]
                    val original = import(picture(w, h, random), "IMG_$i-$n.jpg")
                    val preview =
                        import(picture(w / (maxOf(w, h) / 512), h / (maxOf(w, h) / 512), Random(i * 10 + n)), "IMG_$i-$n-preview.jpg")
                    repo.attach(id, original, "image", w, h, preview = preview)
                }
                // Every other one sits in the text, like pictures added while writing.
                if (i % 10 == 0) {
                    val blocks = (listOf(TITLES[i % TITLES.size]) + lines).mapIndexed { n, it ->
                        TextBlock("b$n", children = listOf(Span(it)))
                    }
                    val placed = blocks.take(2) + AttachmentBlock("p$i", pictures.first()) + blocks.drop(2)
                    repo.saveDraft(id, DocumentCodec.encode(Document(placed)))
                    repo.commitDraft(id)
                }
            }
            if (i % 4 == 0) repo.toggleReaction(id, REACTIONS[i % REACTIONS.size], true)
            if (i % 6 == 0) repo.addTag(id, TAGS[i % TAGS.size])
            if (i % 7 == 0) repeat(1 + random.nextInt(3)) { repo.createComment(id, day, PHRASES[random.nextInt(PHRASES.size)]) }
            if (i % 9 == 0) repo.createCheckin(day, 1 + random.nextInt(5), listOf("спокойствие"), "")
        }
        repo.createNote(today.toString(), "Длинная запись\n" + List(60) { PHRASES[it % PHRASES.size] }.joinToString("\n"))
    }

    /** A soft two-color gradient with a few circles: something to decode, nothing personal. */
    private fun picture(w: Int, h: Int, random: Random): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), color(random), color(random), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        repeat(12) {
            paint.color = color(random) and 0x7FFFFFFF
            canvas.drawCircle(random.nextFloat() * w, random.nextFloat() * h, (0.05f + random.nextFloat() * 0.2f) * w, paint)
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray().also { bitmap.recycle() }
    }

    private fun color(random: Random) = (0xFF shl 24) or random.nextInt(0xFFFFFF)

    private companion object {
        const val ENTRIES = 300

        /** Phone photos (4:3 both ways, 12 MP), a panorama and a screenshot. */
        val SHAPES = listOf(4000 to 3000, 3000 to 4000, 4000 to 3000, 1080 to 2340, 4000 to 1500)
        val TITLES = listOf("Утро", "Прогулка у моря", "Работа", "Вечер с книгой", "Поездка", "Мысли", "Кофе и планы", "Выходные")
        val PHRASES = listOf(
            "Проснулся рано, за окном туман и тишина.",
            "Долго гуляли вдоль берега, ветер был холодный, но солнечный.",
            "Записал пару идей для проекта, кажется, наконец понял, как устроить синхронизацию.",
            "Прочитал главу про остров сокровищ: Джим опять подслушивает из бочки с яблоками.",
            "> Лучше поздно, чем никогда, но лучше никогда не поздно.",
            "Купили новый чайник, старый честно отслужил восемь лет.",
            "Вечером созвонились с родителями, у них всё хорошо.",
            "Список на завтра: почта, спортзал, дочитать статью, полить цветы.",
            "Дождь весь день. Хороший повод никуда не ходить и разобрать фотографии.",
            "Попробовал новый рецепт пасты, получилось лучше, чем ожидал.",
        )
        val REACTIONS = listOf("❤️", "😊", "🔥", "👍")
        val TAGS = listOf("работа", "семья", "книги", "путешествия", "идеи")
    }
}
