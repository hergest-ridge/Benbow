plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.spotless)
}

// Formatting gate: `./gradlew spotlessApply` fixes, `check` runs `spotlessCheck`. Rules live in .editorconfig.
spotless {
    // Sources are LF (.gitattributes); explicit, so Spotless doesn't scan git attributes of every file.
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    // .claude/ holds the agent's worktrees: whole copies of the repository, not ours to format (and minutes to walk).
    val excluded = listOf("**/build/**", "**/.gradle/**", "**/.kotlin/**", ".tmp/**", "kotlin-js-store/**", ".claude/**")
    kotlin {
        target("**/*.kt")
        targetExclude(excluded)
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude(excluded)
        ktlint(libs.versions.ktlint.get())
    }
}
