package app.benbow.server.api

import app.benbow.core.api.DiskSpace
import app.benbow.core.api.StorageKind
import app.benbow.core.api.StorageState
import app.benbow.server.storage.StorageReports
import kotlin.math.roundToLong

// «Место» on the admin page (ADR-041, 7.18 M4): the count the apps get from GET /api/v1/storage, as bars with a legend
// that also says every number (the strict CSP allows no inline styles, so the bars are SVG with classes).

private val KIND_NAMES = mapOf(
    StorageKind.VIDEO to "Видео",
    StorageKind.IMAGE to "Фото",
    StorageKind.SOUND to "Звуки и голос",
    StorageKind.DRAWING to "Рисунки",
    StorageKind.FILE to "Файлы",
    StorageKind.PREVIEW to "Превью и картинки ссылок",
)

private val STATE_NAMES = mapOf(
    StorageState.LIVE to "В записях",
    StorageState.TRASH to "В корзине",
    StorageState.REMOVED to "Сняты с записей",
    StorageState.UNREFERENCED to "Без записи",
)

internal fun spaceSection(storage: StorageReports): String = buildString {
    append("<section id=\"space\" class=\"page\">")
    append(pageTitle("Место", "disk", "Сколько весят файлы на сервере: каждый файл один раз, где бы он ни был приложен."))
    append("<div class=\"card\">")
    val report = runCatching { storage.report() }.getOrElse { e ->
        append(
            "<div class=\"banner err\">${icon(
                "error",
            )}<div>Не удалось посчитать: ${esc(e.message ?: e::class.simpleName.orEmpty())}</div></div>",
        )
        append("</div></section>")
        return@buildString
    }
    append("<div class=\"hero\">${size(report.bytes)}</div>")
    append("<p class=\"muted\">${report.files} ${files(report.files)} · посчитано ${time(report.counted)}</p>")
    report.disk?.let { append(diskBar("Диск с медиа", it, report.bytes)) }
    append("<h3>Что это</h3>")
    append(bar(StorageKind.ALL.map { "k-$it" to (report.kinds[it] ?: 0) }, report.bytes))
    append(legend(StorageKind.ALL.map { Triple("k-$it", KIND_NAMES.getValue(it), report.kinds[it] ?: 0) }, report.bytes))
    append("<h3>Где оно</h3>")
    append(bar(StorageState.ALL.map { "s-$it" to (report.states[it] ?: 0) }, report.bytes))
    append(legend(StorageState.ALL.map { Triple("s-$it", STATE_NAMES.getValue(it), report.states[it] ?: 0) }, report.bytes))
    append("<dl class=\"kv\"><dt>База данных</dt><dd>${size(report.database)}</dd>")
    append("<dt>Снимки базы</dt><dd>${size(report.snapshots)}</dd>")
    append(
        "<dt>Второй диск</dt><dd>${report.mirror?.let {
            "свободно ${size(it.free)} из ${size(it.total)}"
        } ?: "не настроен или не найден"}</dd>",
    )
    append("</dl></div></section>")
}

/** The disk as three parts: Benbow's files, everything else on it, what is free. */
private fun diskBar(title: String, disk: DiskSpace, ours: Long): String {
    val other = (disk.total - disk.free - ours).coerceAtLeast(0)
    val parts = listOf(Triple("d-ours", "Benbow", ours), Triple("d-other", "Другое", other), Triple("d-free", "Свободно", disk.free))
    return "<h3>$title</h3>" + bar(parts.map { it.first to it.third }, disk.total) + legend(parts, disk.total)
}

/** A bar of [parts] (class to bytes) in proportion, 2 px apart; empty parts leave no gap. */
private fun bar(parts: List<Pair<String, Long>>, total: Long): String = buildString {
    val shown = parts.filter { it.second > 0 }
    append("<svg class=\"bar\" viewBox=\"0 0 1000 16\" preserveAspectRatio=\"none\" aria-hidden=\"true\">")
    if (total > 0 && shown.isNotEmpty()) {
        val gap = 4.0
        val room = 1000 - gap * (shown.size - 1)
        var x = 0.0
        for ((key, bytes) in shown) {
            val width = (room * bytes / total).coerceAtLeast(1.0)
            append("<rect class=\"seg $key\" x=\"${"%.1f".format(java.util.Locale.ROOT, x)}\" y=\"0\" ")
            append("width=\"${"%.1f".format(java.util.Locale.ROOT, width)}\" height=\"16\"/>")
            x += width + gap
        }
    }
    append("</svg>")
}

/** Every part with its colour, name, weight and share: the bar's numbers in words (none hides in a colour). */
private fun legend(parts: List<Triple<String, String, Long>>, total: Long): String = buildString {
    append("<div class=\"legend\">")
    for ((key, name, bytes) in parts) {
        if (bytes <= 0) continue
        val share = if (total > 0) (bytes * 100.0 / total).roundToLong() else 0
        append("<div class=\"legend-row\"><span class=\"dot $key\"></span><span>${esc(name)}</span>")
        append("<b>${size(bytes)}</b><span class=\"muted\">${if (share == 0L) "<1" else share} %</span></div>")
    }
    append("</div>")
}

/** «640 КБ», «3,4 МБ», «12 ГБ», as the apps say it. */
internal fun size(bytes: Long): String {
    val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val tenths = (value * 10).roundToLong()
    val number = if (unit == 0 || value >= 10 || tenths % 10 == 0L) value.roundToLong().toString() else "${tenths / 10},${tenths % 10}"
    return "$number${Char(0x00A0)}${units[unit]}" // never split from its unit
}

private fun files(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "файл"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "файла"
    else -> "файлов"
}
