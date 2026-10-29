package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.benbow.app.resources.Res
import app.benbow.app.resources.months_genitive
import app.benbow.client.ApiException
import app.benbow.client.PairingException
import app.benbow.core.api.FriendPost
import app.benbow.core.api.FriendsFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringArrayResource
import kotlin.time.Clock
import kotlin.time.Instant

/** «Лента друзей (проба)» is a tab in the browser only (ADR-056): a prototype to look at, rolled back later. */
internal val AppServices.friendsTab: Boolean get() = platform == "web"

/** How often the feed is asked for while it is shown: letters come within seconds while friends talk. */
private const val POLL_MS = 4_000L

/**
 * «Лента друзей (проба)» (ADR-056): posts between friends' servers, carried as the link's test notes through the mailbox,
 * nothing of the journal. Plain text, every post to every friend; the feed is asked for again every few seconds while
 * it is shown, so a friend's post appears by itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendsFeedScreen(services: AppServices) {
    var feed by remember { mutableStateOf<FriendsFeed?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        try {
            feed = services.friendsFeedReader()
            problem = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = friendsProblem(e)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(POLL_MS)
        }
    }
    val friends = feed?.friends.orEmpty()
    val publish = {
        val text = draft.trim()
        if (text.isNotEmpty() && !sending && friends.isNotEmpty()) {
            sending = true
            scope.launch {
                try {
                    services.friendsPoster(text)
                    draft = ""
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problem = friendsProblem(e)
                } finally {
                    sending = false
                }
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text("Лента друзей")
                Text(
                    "проба · посты идут письмами через общий ящик",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item("composer") { Composer(draft, { draft = it }, sending, friends.map { it.name }, publish) }
                problem?.let { text -> item("problem") { Notice(text, error = true) } }
                when {
                    feed == null && problem == null -> item("loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }

                    feed != null && friends.isEmpty() -> item("alone") {
                        Notice("Пока ни одного друга. Обменяйтесь карточками на странице сервера, в разделе «Друзья».")
                    }

                    feed?.posts?.isEmpty() == true -> item("empty") { Notice("Постов пока нет. Напишите первый — он уйдёт всем друзьям.") }
                }
                val names = friends.associate { it.id to it.name }
                items(feed?.posts.orEmpty(), key = { it.id }) { post -> PostCard(post, names, Modifier.animateItem()) }
            }
        }
    }
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, sending: Boolean, friends: List<String>, publish: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                draft,
                onDraft,
                Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                    val send = e.type == KeyEventType.KeyDown && e.key == Key.Enter && (e.isCtrlPressed || e.isMetaPressed)
                    if (send) publish()
                    send
                },
                placeholder = { Text("Что нового?") },
                minLines = 2,
                maxLines = 10,
                shape = RoundedCornerShape(14.dp),
                enabled = friends.isNotEmpty(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (friends.isEmpty()) "Некому отправить" else "Увидят: ${friends.joinToString()} · Ctrl+Enter",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Button(publish, Modifier.hand(), enabled = draft.isNotBlank() && !sending && friends.isNotEmpty()) {
                    if (sending) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                    } else {
                        Icon(Icons.Send, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.size(8.dp))
                    Text("Опубликовать")
                }
            }
        }
    }
}

@Composable
private fun PostCard(post: FriendPost, names: Map<String, String>, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val mine = post.from == null
    val name = if (mine) "Вы" else names[post.from] ?: "Друг"
    Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, mine)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(whenText(post.at), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            SelectionContainer { Text(post.text, style = MaterialTheme.typography.bodyLarge) }
            if (mine) Delivery(post.to, names)
        }
    }
}

@Composable
private fun Avatar(name: String, mine: Boolean) {
    val colors = MaterialTheme.colorScheme
    val (back, front) = if (mine) {
        colors.primaryContainer to colors.onPrimaryContainer
    } else {
        listOf(
            colors.tertiaryContainer to colors.onTertiaryContainer,
            colors.secondaryContainer to colors.onSecondaryContainer,
        )[name.hashCode().mod(2)]
    }
    Box(Modifier.size(38.dp).background(back, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = front, style = MaterialTheme.typography.titleMedium)
    }
}

/** How far one's post got: one line when every friend is at the same step, else a step per friend. */
@Composable
private fun Delivery(to: Map<String, String>, names: Map<String, String>) {
    val steps = to.values.toSet()
    val lines = if (steps.size == 1) listOf(null to steps.single()) else to.map { (id, step) -> (names[id] ?: "друг") to step }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((who, step) in lines) {
            val (icon, text, tint) = when (step) {
                FriendPost.DELIVERED -> Triple(Icons.DoneAll, "доставлено", MaterialTheme.colorScheme.primary)
                FriendPost.SENT -> Triple(Icons.Check, "в ящике", MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Triple(Icons.Schedule, "ждёт отправки", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StepLine(icon, if (who == null) text else "$who: $text", tint)
        }
    }
}

@Composable
private fun StepLine(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (error) colors.errorContainer else colors.surfaceContainer,
        contentColor = if (error) colors.onErrorContainer else colors.onSurfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium) }
}

/** «только что», «12 мин назад», «сегодня, 14:05», «вчера, 9:12», «12 октября, 14:05». */
@Composable
private fun whenText(at: Long): String {
    val months = stringArrayResource(Res.array.months_genitive)
    val now = Clock.System.now()
    val minutes = (now.toEpochMilliseconds() - at) / 60_000
    if (minutes < 1) return "только что"
    if (minutes < 60) return "$minutes мин назад"
    val zone = TimeZone.currentSystemDefault()
    val time = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone)
    val today = now.toLocalDateTime(zone).date
    val clock = "${time.hour}:${time.minute.toString().padStart(2, '0')}"
    return when (today.toEpochDays() - time.date.toEpochDays()) {
        0L -> "сегодня, $clock"
        1L -> "вчера, $clock"
        else -> "${dateLabel(time.date.toString(), months)}, $clock"
    }
}

private fun friendsProblem(e: Exception): String = when {
    e is PairingException -> "Это устройство не подключено к серверу."
    e is ApiException && e.status == 404 -> "Сервер ещё не знает ленту друзей: обновите его."
    e is ApiException && e.status == 503 -> "Связь с друзьями на сервере не открылась."
    e is ApiException && e.status == 409 -> "Пока ни одного друга."
    else -> "Сервер не отвечает. Лента обновится сама, когда связь вернётся."
}
