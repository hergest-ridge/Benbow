package app.benbow.client

import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.STYLE_NORMAL
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import app.benbow.core.uuidV7
import kotlin.math.absoluteValue
import kotlin.random.Random

/** What `{{placeholders}}` become when a template is used: {{date}}, {{weekday}}, {{month}}, {{prompt}}. */
data class TemplateContext(val date: String, val weekday: String, val month: String, val prompt: String, val kindness: String = "")

data class TemplateBlock(val text: String, val style: String = STYLE_NORMAL, val list: String? = null)

/** A starting point for an entry (ux.md#psychology); optional, never forced. */
data class Template(val id: String, val name: String, val blocks: List<TemplateBlock>)

private fun h1(text: String) = TemplateBlock(text, "h1")

private fun h2(text: String) = TemplateBlock(text, "h2")

private fun p(text: String = "") = TemplateBlock(text)

private fun numbered() = TemplateBlock("", list = "number")

private fun bullet() = TemplateBlock("", list = "bullet")

private fun check() = TemplateBlock("", list = "check")

object Templates {
    /** Answering the question of the day. */
    val PROMPT = Template("prompt", "Вопрос дня", listOf(h2("{{prompt}}"), p()))

    /** After a hard check-in: a kinder question (self-compassion). */
    val KINDNESS = Template("kindness", "Доброта к себе", listOf(h2("{{kindness}}"), p()))

    val builtIn: List<Template> = listOf(
        Template(
            "today",
            "Итоги дня",
            listOf(
                h1(
                    "{{weekday}}, {{date}}",
                ),
                h2("Что было хорошего"), p(), h2("Что было трудно"), p(), h2("Что я понял"), p(), h2("Завтра"), check(),
            ),
        ),
        Template(
            "gratitude",
            "Три хороших события",
            listOf(h2("Три хороших события"), numbered(), numbered(), numbered(), h2("Почему они случились"), p()),
        ),
        Template("free", "Свободное письмо", listOf(p("{{prompt}} Пишите минут пятнадцать, не останавливаясь и не исправляя."), p())),
        Template(
            "thought",
            "Разбор мысли",
            listOf(
                h2(
                    "Ситуация",
                ),
                p(),
                h2(
                    "Мысль",
                ),
                p(),
                h2(
                    "Чувство и его сила, 0–100",
                ),
                p(), h2("Что говорит за эту мысль"), p(), h2("Что против"), p(), h2("Более взвешенный взгляд"), p(),
            ),
        ),
        Template(
            "week",
            "Итоги недели",
            listOf(
                h1(
                    "Неделя до {{date}}",
                ),
                h2(
                    "Что получилось",
                ),
                bullet(), h2("Что было трудно"), bullet(), h2("Чему я научился"), p(), h2("На следующую неделю"), check(),
            ),
        ),
        Template(
            "month",
            "Итоги месяца",
            listOf(h1("{{month}}"), h2("Главное за месяц"), bullet(), h2("Чем я горжусь"), p(), h2("Что хочу изменить"), p()),
        ),
        Template(
            "future",
            "Письмо себе через год",
            listOf(h2("Привет, я через год"), p("Сейчас {{date}}, и вот что у меня происходит…"), p()),
        ),
    )

    fun byId(id: String): Template? = (builtIn + PROMPT + KINDNESS).firstOrNull { it.id == id }

    /** The template as a Benbow Document, placeholders filled in. */
    fun render(template: Template, context: TemplateContext, random: Random = Random.Default): String = DocumentCodec.encode(
        Document(
            template.blocks.map { block ->
                TextBlock(
                    key = uuidV7(0, random).takeLast(8),
                    style = block.style,
                    listItem = block.list,
                    level = block.list?.let { 1 },
                    checked = if (block.list == "check") false else null,
                    children = listOf(Span(fill(block.text, context))),
                )
            },
        ),
    )

    /** [text] as a heading and an empty line to write on: an entry about a calendar event ("Написать об этом"). */
    fun heading(text: String, random: Random = Random.Default): String = render(
        Template("heading", "", listOf(h2(text), p())),
        TemplateContext("", "", "", ""),
        random,
    )

    /** Placeholders kept as they are: a standard template copied into one's own, to be filled when used. */
    val KEEP = TemplateContext("{{date}}", "{{weekday}}", "{{month}}", "{{prompt}}", "{{kindness}}")

    /** Every placeholder [document]'s words have, filled in (one's own templates, ADR-061). */
    fun fill(document: Document, context: TemplateContext): Document = document.copy(
        blocks = document.blocks.map { block ->
            if (block is TextBlock) block.copy(children = block.children.map { it.copy(text = fill(it.text, context)) }) else block
        },
    )

    private fun fill(text: String, c: TemplateContext) = text.replace(
        "{{date}}",
        c.date,
    ).replace(
        "{{weekday}}",
        c.weekday,
    ).replace("{{month}}", c.month).replace("{{prompt}}", c.prompt).replace("{{kindness}}", c.kindness)
}

/** Words for feelings (emotional granularity): pleasant first, then hard ones. */
val EMOTION_WORDS = listOf(
    "радость", "спокойствие", "благодарность", "интерес", "гордость", "нежность", "надежда", "облегчение",
    "усталость", "тревога", "грусть", "раздражение", "злость", "одиночество", "растерянность", "скука", "стыд",
)

/** Questions grouped by the practice they come from (ux.md#psychology). */
object Prompts {
    val byPractice: Map<String, List<String>> = mapOf(
        "Благодарность" to listOf(
            "За что вы благодарны сегодня, даже за мелочь?",
            "Кто сделал ваш день немного лучше?",
            "Что хорошего случилось, что легко было не заметить?",
        ),
        "Размышление" to listOf(
            "Что сегодня занимало больше всего мыслей?",
            "Какое решение далось непросто и почему?",
            "Чему этот день вас научил?",
        ),
        "Взгляд со стороны" to listOf(
            "Как бы этот день описал добрый друг, глядя со стороны?",
            "Что бы вы сказали себе год назад?",
            "Что из сегодняшнего будет важно через пять лет?",
        ),
        "Смакование" to listOf(
            "Какой момент сегодня хочется запомнить?",
            "Чего приятного вы ждёте завтра?",
            "Какое воспоминание сегодня всплыло?",
        ),
        "Доброта к себе" to listOf(
            "Что бы вы сказали другу в такой же ситуации?",
            "В чём вы сегодня были к себе слишком строги?",
            "Что поможет вам сегодня отдохнуть?",
        ),
        "Маленькие победы" to listOf(
            "Какой маленький шаг вперёд вы сделали?",
            "Что получилось лучше, чем вчера?",
            "Что удалось довести до конца?",
        ),
    )

    private val all = byPractice.values.flatten()

    /** The question of the day: the same all day, a different one tomorrow. */
    fun ofTheDay(day: String): String = all[day.hashCode().absoluteValue % all.size]

    /** After a hard mood check-in: a kinder question (self-compassion). */
    fun kindness(day: String): String = byPractice.getValue("Доброта к себе").let { it[day.hashCode().absoluteValue % it.size] }
}
