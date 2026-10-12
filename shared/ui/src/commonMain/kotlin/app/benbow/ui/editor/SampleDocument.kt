package app.benbow.ui.editor

import app.benbow.core.text.Align
import app.benbow.core.text.BlockStyle
import app.benbow.core.text.Callout
import app.benbow.core.text.ListKind
import app.benbow.core.text.Mark
import app.benbow.core.text.RichText
import app.benbow.core.text.SizeStep
import app.benbow.core.text.buildRichText

/** Spike content that exercises every v1 formatting feature. */
internal fun sampleDocument(): RichText = buildRichText {
    paragraph(style = BlockStyle.H1) { text("Вечер в «Адмирале Бенбоу»") }
    paragraph {
        text("Сегодня было ")
        text("очень", Mark.Bold)
        text(" тихо. За окном ")
        text("шумело море", Mark.Italic)
        text(", а в камине потрескивали дрова.")
    }
    paragraph {
        text("Можно ")
        text("подчеркнуть", Mark.Underline)
        text(", ")
        text("зачеркнуть", Mark.Strike)
        text(", выделить ")
        text("маркером", Mark.Highlight("yellow"))
        text(", покрасить в ")
        text("морской синий", Mark.Color("blue"))
        text(", вставить ")
        text("код", Mark.Code)
        text(" или спрятать ")
        text("спойлер", Mark.Spoiler)
        text(".")
    }
    paragraph {
        text("Размеры: ")
        text("мелкий", Mark.Size(SizeStep.Small))
        text(", обычный, ")
        text("крупный", Mark.Size(SizeStep.Large))
        text(", ")
        text("огромный", Mark.Size(SizeStep.XLarge))
        text(".")
    }
    paragraph(style = BlockStyle.H2) { text("Список дел") }
    paragraph(list = ListKind.Check, checked = true) { text("Проверить, как работает редактор") }
    paragraph(list = ListKind.Check) { text("Записать мысль про карту острова") }
    paragraph(list = ListKind.Check)
    paragraph(list = ListKind.Bullet) { text("Маркированный пункт") }
    paragraph(list = ListKind.Bullet, level = 2) { text("Вложенный пункт") }
    paragraph(list = ListKind.Number) { text("Первый") }
    paragraph(list = ListKind.Number) { text("Второй") }
    paragraph(style = BlockStyle.Quote) { text("«Пятнадцать человек на сундук мертвеца» — старая песня, которую пел капитан.") }
    paragraph(callout = Callout("sample", "orange", "💡")) { text("Выноска: здесь удобно держать важное.") }
    paragraph(callout = Callout("sample", "orange", "💡")) { text("Она может занимать несколько абзацев.") }
    paragraph(style = BlockStyle.H3, align = Align.Center) { text("По центру") }
    paragraph(style = BlockStyle.Code) { text("val treasure = map.find(\"X\")") }
    paragraph { text("Попробуй в начале строки «# », «- », «[] », «> », а в тексте — **жирный**, *курсив*, `код`.") }
}
