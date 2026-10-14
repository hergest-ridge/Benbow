package app.benbow.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Frame times while skimming the feed and opening posts (budget: no janky frames at 120 Hz, docs/ux.md). */
@RunWith(AndroidJUnit4::class)
class FeedBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun seed() = seedJournal()

    @Test
    fun skimFeed() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            startActivityAndWait()
            waitForHome()
            openFeed()
        },
    ) { skim() }

    @Test
    fun openPosts() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            startActivityAndWait()
            waitForHome()
            openFeed()
        },
    ) { repeat(3) { openPostAndBack() } }
}
