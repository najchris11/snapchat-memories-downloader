plugins {
    kotlin("multiplatform") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.android.application") version "8.13.2" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

// Static analysis. Round 1 of the UI audit removed eleven unused imports, two public
// composables nothing called, a duplicated theme constant and an unused function
// parameter — every one of which a default rule set flags in a single run. This exists so
// that class of thing is caught by the build rather than by reading the diff.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    detekt {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // Baseline scoped to a real pre-existing backlog, not a blanket amnesty. detekt
        // found 23 places where a failure vanishes — 16 swallowed exceptions, 6 printed
        // stack traces, 1 generic throw — all in pipeline and platform code, none in the
        // UI. Fixing them properly means threading real logging through the pipeline, which
        // is its own piece of work. Baselining those exact sites means anything NEW fails
        // the build while the backlog stays visible and countable.
        baseline = rootProject.file("config/detekt/baseline.xml")
        ignoreFailures = false
        source.setFrom(
            "src/commonMain/kotlin",
            "src/desktopMain/kotlin",
            "src/androidMain/kotlin",
            "src/iosMain/kotlin",
            "src/jvmSharedMain/kotlin",
            "src/commonTest/kotlin",
            "src/desktopTest/kotlin",
        )
    }
}

