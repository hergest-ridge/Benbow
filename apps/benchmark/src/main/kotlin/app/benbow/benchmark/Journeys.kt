package app.benbow.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** The non-debuggable bench build with its own DB; benchmarks never touch the owner's app.benbow. */
const val PACKAGE = "app.benbow.bench"

private const val TIMEOUT = 10_000L
private const val SETTLE_MS = 1_500L
private const val SEED_MILLIS = 20 * 60_000L

/**
 * Fills the bench build's journal once (made-up entries and drawn pictures), then stops it so the next start is cold.
 * `-Pandroid.testInstrumentationRunnerArguments.seedYears=N` adds N years of older posts (a long feed, ADR-036).
 */
fun seedJournal() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val years = InstrumentationRegistry.getArguments().getString("seedYears")?.toIntOrNull() ?: 0
    device.executeShellCommand("am start -W -n $PACKAGE/app.benbow.android.BenchSeedActivity --ei years $years")
    // `am start -W` gives up after 10 s, long before the pictures (or years of posts) are in: wait for the seed to finish.
    val until = SystemClock.uptimeMillis() + SEED_MILLIS
    while (SystemClock.uptimeMillis() < until && "BenchSeedActivity" in device.executeShellCommand("dumpsys activity activities")) {
        SystemClock.sleep(1_000)
    }
    device.executeShellCommand("am force-stop $PACKAGE")
}

fun MacrobenchmarkScope.waitForHome() {
    device.wait(Until.hasObject(By.text("Лента")), TIMEOUT)
    device.waitForIdle()
}

fun MacrobenchmarkScope.openTab(label: String) {
    device.wait(Until.findObject(By.text(label)), TIMEOUT).click()
    device.waitForIdle()
}

fun MacrobenchmarkScope.openFeed() {
    openTab("Лента")
    device.wait(Until.hasObject(By.text("Моя лента")), TIMEOUT)
}

/**
 * Flings the screen's list down, then back up, the way one skims a feed. Plain swipes: UiObject2.fling waits for
 * a scroll event Compose doesn't send, 5 s per fling.
 */
fun MacrobenchmarkScope.skim(flings: Int = 5) {
    device.wait(Until.hasObject(By.scrollable(true)), TIMEOUT)
    val x = device.displayWidth / 2
    val top = device.displayHeight / 4
    val bottom = device.displayHeight * 3 / 4
    repeat(flings) {
        device.swipe(x, bottom, x, top, 8)
        device.waitForIdle()
    }
    repeat(flings) {
        device.swipe(x, top, x, bottom, 8)
        device.waitForIdle()
    }
}

/** Words of the seeded posts' sentences, so a tap lands on a post's text and not on a title bar. */
private val POST_TEXT = Pattern.compile("(?s).*(туман|берега|идей|главу|чайник|родителями|Список|Дождь|рецепт|поздно).*")

/** Opens the first post on screen by its text and goes back to the feed. */
fun MacrobenchmarkScope.openPostAndBack() {
    // A tap on a list still gliding only stops it, so let the last fling settle; then tap the most visible text.
    SystemClock.sleep(SETTLE_MS)
    device.wait(Until.hasObject(By.text(POST_TEXT)), TIMEOUT)
    device.findObjects(By.text(POST_TEXT)).maxBy { it.visibleBounds.height() }.click()
    check(device.wait(Until.hasObject(By.text("Комментарий…")), TIMEOUT)) { "the post didn't open" }
    device.waitForIdle()
    device.pressBack()
    device.wait(Until.hasObject(By.text("Моя лента")), TIMEOUT)
    device.waitForIdle()
}

/** Types into the focused field, waits for results to settle. */
fun MacrobenchmarkScope.search(query: String) {
    device.executeShellCommand("input text $query")
    SystemClock.sleep(SETTLE_MS)
    device.waitForIdle()
}
