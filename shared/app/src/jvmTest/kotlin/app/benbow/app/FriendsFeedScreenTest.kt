package app.benbow.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.client.ApiException
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.benbow.core.api.FriendInfo
import app.benbow.core.api.FriendPost
import app.benbow.core.api.FriendsFeed
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/** «Лента друзей (проба)» (ADR-056): a tab in the browser, posts both ways, one's own going out, a friend's arriving. */
@OptIn(ExperimentalTestApi::class)
class FriendsFeedScreenTest {
    private val now = System.currentTimeMillis()
    private val friends = listOf(FriendInfo("anna", "Аня", "0.19.1"), FriendInfo("max", "Макс", "0.19.1"))
    private val posts = mutableListOf(
        FriendPost(
            "in-anna-1",
            "anna",
            "Вчера были на даче, наконец-то дозрели яблоки. Сварили варенье, весь дом пахнет корицей.",
            now - 26 * 3_600_000,
        ),
        FriendPost(
            "out-anna-1",
            null,
            "Завидую! У нас третий день дождь, читаю «Сто лет одиночества».",
            now - 2 * 3_600_000,
            mapOf("anna" to FriendPost.DELIVERED, "max" to FriendPost.SENT),
        ),
        FriendPost("in-max-1", "max", "Хорошая книга. Как дочитаешь — расскажи про финал.", now - 25 * 60_000),
    )
    private val sent = mutableListOf<String>()

    private fun services(platform: String = "web") = AppServices(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
        mediaIn(java.nio.file.Files.createTempDirectory("benbow-media").toFile()),
        platform,
        "Test PC",
    ).apply {
        friendsFeedReader = { FriendsFeed(friends, posts.sortedByDescending { it.at }) }
        friendsPoster = { text ->
            sent += text
            posts +=
                FriendPost(
                    "out-${sent.size + 1}",
                    null,
                    text,
                    System.currentTimeMillis(),
                    friends.associate {
                        it.id to FriendPost.WAITING
                    },
                )
        }
    }

    @Test
    fun postsGoOutAndAFriendsPostArrivesByItself() = runDesktopComposeUiTest(width = 1100, height = 760) {
        setContent { BenbowApp(services()) }
        onNodeWithText("Друзья").click()
        waitUntilAtLeastOneExists(hasText("Сварили варенье", substring = true), 5_000)
        screenshot("friends-feed")

        onNode(hasSetTextAction()).performTextInput("Дочитал! Финал — лучшее, что я читал в этом году.")
        onNodeWithText("Опубликовать").click()
        waitUntilAtLeastOneExists(hasText("ждёт отправки"), 5_000)
        assertEquals(listOf("Дочитал! Финал — лучшее, что я читал в этом году."), sent)

        posts += FriendPost("in-anna-2", "anna", "Ура! Тогда в субботу обсудим за чаем?", System.currentTimeMillis())
        waitUntilAtLeastOneExists(hasText("в субботу обсудим", substring = true), 10_000)
        screenshot("friends-feed-live")
    }

    @Test
    fun aServerThatDoesntKnowTheFeedSaysToUpdate() = runDesktopComposeUiTest(width = 412, height = 900) {
        val services = services().apply { friendsFeedReader = { throw ApiException(404, null, "Not found") } }
        setContent { BenbowApp(services) }
        onNodeWithText("Друзья").click()
        waitUntilAtLeastOneExists(hasText("обновите его", substring = true), 5_000)
        screenshot("friends-feed-phone-width")
    }

    @Test
    fun thereIsNoFriendsTabOutsideTheBrowser() = runDesktopComposeUiTest(width = 1100, height = 760) {
        setContent { BenbowApp(services(platform = "desktop")) }
        waitUntilAtLeastOneExists(hasText("Трекеры"), 5_000)
        assertEquals(0, onAllNodes(hasText("Друзья")).fetchSemanticsNodes().size)
    }
}
