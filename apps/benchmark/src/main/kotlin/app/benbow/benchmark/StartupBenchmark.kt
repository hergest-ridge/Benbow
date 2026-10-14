package app.benbow.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cold start to the first frame of Home (budget: under 600 ms on the owner's S24+, docs/ux.md). */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun seed() = seedJournal()

    @Test
    fun withoutCompilation() = startup(CompilationMode.None())

    @Test
    fun withBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        waitForHome()
    }
}
