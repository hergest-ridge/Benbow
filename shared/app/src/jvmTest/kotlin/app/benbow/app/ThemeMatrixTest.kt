package app.benbow.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import app.benbow.ui.theme.OwnPalette
import app.benbow.ui.theme.PaletteCharacter
import app.benbow.ui.theme.PaletteGround
import app.benbow.ui.theme.ThemeContrast
import app.benbow.ui.theme.ThemeMode
import app.benbow.ui.theme.ThemePalette
import kotlin.test.Test

/**
 * The key screens in every kind of theme (ADR-057, S7): presets in both modes, high contrast, black, a palette of one's
 * own. The screenshots land in `build/screens/matrix-*` for review beside the contrast tests, which hold the numbers.
 */
@OptIn(ExperimentalTestApi::class)
class ThemeMatrixTest {
    private class Look(
        val name: String,
        val palette: ThemePalette,
        val mode: ThemeMode,
        val contrast: ThemeContrast = ThemeContrast.Standard,
        val black: Boolean = false,
        val own: OwnPalette? = null,
    )

    private val looks = listOf(
        Look("inn-light", ThemePalette.Inn, ThemeMode.Light),
        Look("inn-dark", ThemePalette.Inn, ThemeMode.Dark),
        Look("inn-light-high", ThemePalette.Inn, ThemeMode.Light, ThemeContrast.High),
        Look("sea-light", ThemePalette.Sea, ThemeMode.Light),
        Look("forest-dark-black", ThemePalette.Forest, ThemeMode.Dark, black = true),
        Look(
            "own-light",
            ThemePalette.Own,
            ThemeMode.Light,
            own = OwnPalette(Color(0xFF8E5BA8), PaletteCharacter.Bright, PaletteGround.Cool),
        ),
    )

    @Test
    fun theKeyScreensInEveryKindOfTheme() = looks.forEach { look ->
        runDesktopComposeUiTest(width = 412, height = 860) {
            val services = feedTestServices()
            val today = services.today()
            services.repo.createNote(today, "Утро у моря\nЧай на балконе, чайки, ветер пахнет водорослями.")
            services.repo.setBackground(services.repo.createNote(today, "На выходные\nКупить ниток и пуговиц."), "sand")
            services.repo.setBackground(services.repo.createNote(today, "Письмо другу\nДописать вечером."), "fog")
            services.setAppearance(services.appearance.value.copy(mode = look.mode, contrast = look.contrast, black = look.black))
            services.choosePalette(look.palette, look.own)
            waitUntil(timeoutMillis = 5_000) { services.themeChoiceNow().palette == look.palette.name }
            setContent { BenbowApp(services) }
            onNodeWithText("Лента").click()
            waitUntilAtLeastOneExists(hasText("Утро у моря", substring = true), 5_000)
            waitForIdle()
            screenshot("matrix-${look.name}-feed")
            onNodeWithText("Главная").click()
            waitForIdle()
            screenshot("matrix-${look.name}-home")
            onNodeWithText("Ещё").click()
            onNodeWithText("Оформление").click()
            waitUntilAtLeastOneExists(hasText("Контраст"), 5_000)
            waitForIdle()
            screenshot("matrix-${look.name}-appearance")
            if (look.palette == ThemePalette.Own) {
                onNodeWithText("Своя · Изменить").click()
                waitUntilAtLeastOneExists(hasText("Своя палитра"), 5_000)
                waitForIdle()
                screenshot("matrix-${look.name}-own-sheet")
            }
        }
    }
}
