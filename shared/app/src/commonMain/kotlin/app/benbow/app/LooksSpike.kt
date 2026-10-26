package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * «Облик записки (проба)» (ADR-047, 7.20 L0): made-up notes in every layer of a look, for the owner and a friend to
 * choose by eye, the feed's muted look and motion switchable. Only where AppServices.labs is on. The texts are made up
 * and live here, not in the strings: the screen goes away with L1.
 */

/** A made-up note in one look; [taped] is a picture under `looks/pictures` and how it is held (`TapedPicture`). */
private class LookSample(
    val name: String,
    val credit: String?,
    val color: String?,
    val look: NoteLook,
    val title: String,
    val text: String,
    val taped: Pair<String, String>? = null,
)

private class LookPart(val title: String, val samples: List<LookSample>)

private const val HERO = "Hero Patterns · CC BY"
private const val AMBIENT = "ambientCG · CC0"
private const val MET = "The Met · CC0"
private const val TABLER = "Tabler Icons · MIT"
private const val OPENCLIPART = "Openclipart · CC0"

private val RECIPE =
    "Паста болоньезе" to "Лук и морковь мелко, фарш до румяности, томаты и щепотка сахара. Варить на медленном огне сорок минут."
private val DAY =
    "Утро у моря" to "Проснулась раньше будильника. Чай на балконе, чайки, ветер пахнет водорослями. Хочу запомнить этот свет."
private val LIST = "На выходные" to "Купить ниток и пуговиц. Позвонить бабушке. Дочитать «Остров сокровищ». Разобрать фотографии."
private val THOUGHT = "Мысль" to "Иногда лучший способ решить задачу — пойти погулять и перестать о ней думать."

private fun sample(
    name: String,
    credit: String?,
    color: String?,
    look: NoteLook,
    words: Pair<String, String>,
    taped: Pair<String, String>? = null,
) = LookSample(name, credit, color, look, words.first, words.second, taped)

private val HERO_NAMES = listOf(
    "graph-paper" to "Миллиметровка",
    "topography" to "Топография",
    "leaf" to "Листья",
    "endless-clouds" to "Облака",
    "wiggle" to "Волнистые",
    "polka-dots" to "Горошек",
    "texture" to "Фактура",
    "wallpaper" to "Обои",
    "bubbles" to "Пузырьки",
    "four-point-stars" to "Звёздочки",
    "autumn" to "Осень",
    "bamboo" to "Бамбук",
    "moroccan" to "Марокко",
    "formal-invitation" to "Приглашение",
    "overlapping-circles" to "Кольца",
    "rain" to "Дождь",
    "lines-in-motion" to "Линии",
    "glamorous" to "Гламур",
    "kiwi" to "Киви",
)
private val HERO_COLORS = listOf(null, "sand", "mint", "fog", "blossom", "clay", "sage", "dusk", "storm", "peach", "coral")
private val WORDS = listOf(DAY, THOUGHT, LIST, RECIPE)

private val PARTS = listOf(
    LookPart(
        "Бумага",
        listOf(
            sample("Точки", null, null, NoteLook(paper = "dots"), LIST),
            sample("Линейка", null, "sand", NoteLook(paper = "lines"), DAY),
            sample("Клетка", null, "fog", NoteLook(paper = "grid"), RECIPE),
            sample("Пергамент", null, "clay", NoteLook(paper = "parchment"), THOUGHT),
        ),
    ),
    LookPart(
        "Бумага · фото ambientCG",
        listOf(
            sample("Белая", AMBIENT, null, NoteLook(paperPhoto = "paper001"), DAY),
            sample("Мятая", AMBIENT, "fog", NoteLook(paperPhoto = "paper003"), LIST),
            sample("Бежевая", AMBIENT, "sand", NoteLook(paperPhoto = "paper006"), THOUGHT),
            sample("Бежевая на глине", AMBIENT, "clay", NoteLook(paperPhoto = "paper006"), RECIPE),
        ),
    ),
    LookPart(
        "Узоры · Hero Patterns",
        HERO_NAMES.mapIndexed { i, (name, title) ->
            sample(title, HERO, HERO_COLORS[i % HERO_COLORS.size], NoteLook(tile = name), WORDS[i % WORDS.size])
        },
    ),
    LookPart(
        "Узоры · свои",
        listOf(
            sample("Круги", null, "blossom", NoteLook(pattern = "circles"), THOUGHT),
            sample("Треугольники", null, "mint", NoteLook(pattern = "triangles"), LIST),
            sample("Волны", null, "fog", NoteLook(pattern = "waves"), DAY),
            sample("Терраццо", null, "sand", NoteLook(pattern = "terrazzo"), RECIPE),
            sample(
                "Россыпь листьев",
                TABLER,
                "sage",
                NoteLook(pattern = "scatter", scatter = listOf("leaf", "plant-2", "seedling", "clover")),
                DAY,
            ),
            sample("Россыпь звёзд", TABLER, "dusk", NoteLook(pattern = "scatter", scatter = listOf("star", "sparkles", "moon")), THOUGHT),
        ),
    ),
    LookPart(
        "Обои Морриса · The Met",
        listOf(
            sample("Ивовая ветвь", MET, "mint", NoteLook(wallpaper = "willow-bough"), DAY),
            sample("Бархатцы", MET, "fog", NoteLook(wallpaper = "marigold"), THOUGHT),
            sample("Маргаритки", MET, "sand", NoteLook(wallpaper = "daisy"), LIST),
            sample("Гранаты", MET, "storm", NoteLook(wallpaper = "fruit"), RECIPE),
        ),
    ),
    LookPart(
        "Скрапбук · рисуется кодом",
        listOf(
            sample("Скотч", null, null, NoteLook(scrap = "tape"), DAY),
            sample("Скотч, другая раскладка", null, "sand", NoteLook(scrap = "tape"), LIST),
            sample("Обрывки", null, "fog", NoteLook(scrap = "scraps"), THOUGHT),
            sample("Марка", null, "mint", NoteLook(scrap = "stamp"), DAY),
            sample("Стежки", null, "blossom", NoteLook(scrap = "stitch"), RECIPE),
            sample("Вырванный лист", null, null, NoteLook(scrap = "torn", paper = "lines"), LIST),
            sample("Коллаж", null, "clay", NoteLook(scrap = "collage"), THOUGHT),
            sample("Коллаж на бумаге", AMBIENT, "sand", NoteLook(scrap = "collage", paperPhoto = "paper006"), DAY),
        ),
    ),
    LookPart(
        "Картинка на скотче · The Met",
        listOf(
            sample("Скотч по углам", MET, null, NoteLook(paper = "dots"), DAY, "wheat-field" to "tape"),
            sample("Полоска сверху", MET, "fog", NoteLook(), THOUGHT, "great-wave" to "strip"),
            sample("Полароид", MET, "sand", NoteLook(scrap = "stitch"), LIST, "wheat-field" to "polaroid"),
            sample("Уголки", "$MET, $AMBIENT", "clay", NoteLook(paperPhoto = "paper006"), RECIPE, "great-wave" to "corners"),
        ),
    ),
    LookPart(
        "Свет",
        listOf(
            sample("Тёплый угол", null, null, NoteLook(lights = listOf(LookLight(315f, "orange", 2))), DAY),
            sample("Два света", null, "fog", NoteLook(lights = listOf(LookLight(225f, "pink", 2), LookLight(45f, "blue", 2))), THOUGHT),
            sample(
                "Три света",
                null,
                "blossom",
                NoteLook(lights = listOf(LookLight(200f, "yellow", 2), LookLight(330f, "pink", 2), LookLight(80f, "purple", 1))),
                LIST,
            ),
            sample("Фонарь", null, "clay", NoteLook(lights = listOf(LookLight(270f, "yellow", 3))), RECIPE),
        ),
    ),
    LookPart(
        "Движение",
        listOf(
            sample("Сумерки", null, null, NoteLook(motion = "drift"), THOUGHT),
            sample("Сумерки на сирени", null, "dusk", NoteLook(motion = "drift"), DAY),
        ),
    ),
    LookPart(
        "Рисунки · Openclipart",
        listOf(
            sample("Ласточка", OPENCLIPART, "fog", NoteLook(art = listOf("openclipart/swallow-silhouette"), artTint = true), DAY),
            sample("Птица на ветке", OPENCLIPART, "sage", NoteLook(art = listOf("openclipart/perched-bird"), artTint = true), THOUGHT),
            sample(
                "Ветка в цвету",
                OPENCLIPART,
                "blossom",
                NoteLook(art = listOf("openclipart/branch-flowers-leaves"), artTint = true),
                LIST,
            ),
            sample("Сакура цветная", OPENCLIPART, "blossom", NoteLook(art = listOf("openclipart/cherry-blossoms")), DAY),
            sample("Птичка-контур", OPENCLIPART, "mint", NoteLook(art = listOf("openclipart/cute-bird-outline"), artTint = true), RECIPE),
            sample("Роза ветров", OPENCLIPART, "clay", NoteLook(art = listOf("openclipart/simple-compass-rose"), artTint = true), THOUGHT),
            sample(
                "Старинная роза ветров",
                OPENCLIPART,
                "clay",
                NoteLook(art = listOf("openclipart/vintage-compass-rose"), artTint = true),
                DAY,
            ),
        ),
    ),
    LookPart(
        "Образы",
        listOf(
            sample(
                "Сакура",
                OPENCLIPART,
                "blossom",
                NoteLook(paper = "dots", art = listOf("openclipart/cherry-blossoms"), lights = listOf(LookLight(315f, "pink", 2))),
                DAY,
            ),
            sample("Осень", HERO, "peach", NoteLook(tile = "autumn", lights = listOf(LookLight(300f, "orange", 2))), LIST),
            sample(
                "Птичник",
                OPENCLIPART,
                "fog",
                NoteLook(
                    paper = "grid",
                    art = listOf("openclipart/swallow-silhouette"),
                    artTint = true,
                    lights = listOf(LookLight(45f, "blue", 2)),
                ),
                THOUGHT,
            ),
            sample(
                "Бенбоу",
                "$AMBIENT, $OPENCLIPART",
                "clay",
                NoteLook(
                    paperPhoto = "paper006",
                    art = listOf("openclipart/vintage-compass-rose"),
                    artTint = true,
                    lights = listOf(LookLight(270f, "yellow", 3)),
                ),
                RECIPE,
            ),
            sample("Ночь", HERO, "storm", NoteLook(tile = "four-point-stars", lights = listOf(LookLight(225f, "purple", 2))), DAY),
            sample(
                "Сумерки",
                TABLER,
                "dusk",
                NoteLook(motion = "drift", pattern = "scatter", scatter = listOf("sparkles", "moon")),
                THOUGHT,
            ),
            sample("Моррис", MET, "mint", NoteLook(wallpaper = "willow-bough", lights = listOf(LookLight(315f, "yellow", 1))), LIST),
            sample("Чертёжная тетрадь", "$HERO, $AMBIENT", null, NoteLook(tile = "graph-paper", paperPhoto = "paper001"), RECIPE),
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LooksSpikeScreen(nav: Navigator) {
    var muted by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(true) }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { BackButton(nav::back) }, title = { Text("Облик записки (проба)") })
    }) { padding ->
        LazyVerticalGrid(
            GridCells.Adaptive(168.dp),
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(muted, { muted = !muted }, { Text("Как в ленте") }, Modifier.hand())
                    FilterChip(moving, { moving = !moving }, { Text("Движение") }, Modifier.hand())
                }
            }
            PARTS.forEach { part ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        part.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                    )
                }
                items(part.samples, key = { part.title + it.name }) { SampleCard(it, muted, moving) }
            }
        }
    }
}

@Composable
private fun SampleCard(sample: LookSample, muted: Boolean, moving: Boolean) {
    val base = swatch(sample.color) ?: boardCardColor()
    val ink = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(12.dp)
    NoteBackdrop(
        sample.look,
        base,
        ink,
        seed = sample.name.hashCode(),
        modifier = Modifier.fillMaxWidth().height(if (sample.taped != null) 280.dp else 196.dp).clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        muted = muted,
        moving = moving,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
            Text(sample.title, style = MaterialTheme.typography.titleMedium, color = ink, maxLines = 1)
            Text(
                sample.text,
                style = MaterialTheme.typography.bodyMedium,
                color = ink,
                maxLines = if (sample.taped != null) 2 else 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            sample.taped?.let { (picture, style) ->
                TapedPicture(
                    "pictures/$picture.jpg",
                    style,
                    sample.name.hashCode(),
                    base,
                    Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp).fillMaxWidth(),
                )
            }
        }
        Box(
            Modifier.align(Alignment.BottomStart).padding(8.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(
                listOfNotNull(sample.name, sample.credit).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}
