package app.benbow.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Collects the code the owner's daily paths run, for apps/android/src/main/baseline-prof.txt. Run against the
 * non-minified build (names as written): ./gradlew :apps:benchmark:connectedProfilingAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=app.benbow.benchmark.BaselineProfileGenerator
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Before
    fun seed() = seedJournal()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE) {
        pressHome()
        startActivityAndWait()
        waitForHome()
        skim(flings = 2)
        openFeed()
        skim(flings = 3)
        openPostAndBack()
        openTab("Поиск")
        search("chai") // the search box takes focus; Latin only, `input text` can't type Cyrillic
        device.pressBack() // the keyboard covers the tabs
        device.waitForIdle()
        openTab("Календарь")
        openTab("Ещё")
        openTab("Главная")
    }
}
