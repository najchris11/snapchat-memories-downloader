import org.jetbrains.compose.desktop.application.dsl.TargetFormat

val appVersion = (project.findProperty("app.version") as? String) ?: "0.0.0"
// packageVersion must be pure semver (no pre-release labels) for DMG/MSI/deb
val packageSemVer = appVersion.replace(Regex("-[a-zA-Z].*"), "").let { v ->
    if (v.count { it == '.' } < 2) "$v.0" else v
}

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.application")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
                implementation(compose.ui)
                implementation(compose.components.resources)
                
                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                
                // HTML parser: kSoup
                implementation("com.fleeksoft.ksoup:ksoup:0.2.6")
                
                // HTTP Client: Ktor
                implementation("io.ktor:ktor-client-core:3.5.0")
                
                // Serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                
                // I/O & Files: Okio
                implementation("com.squareup.okio:okio:3.17.0")
            }
        }
        
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-client-mock:3.5.0")
                implementation("com.squareup.okio:okio-fakefilesystem:3.17.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }

        // Compose UI tests for the desktop target. The UI layer had no test coverage at all,
        // so every layout and state-default regression had to be caught by eye; these let
        // composable behaviour be asserted the same way pipeline behaviour already is.
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.uiTestJUnit4)
                implementation(compose.desktop.currentOs)
            }
        }
        
        // Code shared by the two JVM targets (desktop + Android): java.util.zip readers,
        // JVM actuals for common expect declarations, and anything else JVM-only that
        // both platforms need. iOS gets its own Kotlin/Native actuals instead.
        val jvmSharedMain by creating {
            dependsOn(commonMain)
        }

        val androidMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation("androidx.activity:activity-compose:1.13.0")
                implementation("androidx.appcompat:appcompat:1.7.1")
                implementation("androidx.core:core-ktx:1.18.0")
                implementation("androidx.exifinterface:exifinterface:1.4.2")
                implementation("androidx.documentfile:documentfile:1.1.0")
                // Video overlay burn-in (Transformer + OverlayEffect); MP4 output only.
                implementation("androidx.media3:media3-transformer:1.11.1")
                implementation("androidx.media3:media3-effect:1.11.1")
                implementation("androidx.media3:media3-common:1.11.1")
                implementation("io.ktor:ktor-client-okhttp:3.5.0")
            }
        }

        val androidInstrumentedTest by getting {
            dependencies {
                implementation("androidx.test:runner:1.7.0")
                implementation("androidx.test.ext:junit:1.3.0")
            }
        }

        val desktopMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(compose.preview)
                implementation(compose.desktop.currentOs)
                implementation(compose.foundation)
                implementation("io.ktor:ktor-client-cio:3.5.0")
                implementation("net.iakovlev:timeshape:2026b.29")
            }
        }
        
        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.5.0")
            }
        }
        
        val iosTest by creating { dependsOn(commonTest) }
        val iosArm64Test by getting { dependsOn(iosTest) }
        val iosSimulatorArm64Test by getting { dependsOn(iosTest) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
    }
}

android {
    namespace = "com.najdev.snapvault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.najdev.snapvault"
        minSdk = 24
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 16
        versionName = appVersion
    }
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

compose.desktop {
    application {
        // AS JBR lacks jpackage; JDK 21 (Homebrew) is used for native distribution tasks
        javaHome = (project.findProperty("compose.javaHome") as? String)
            ?: System.getenv("JAVA_HOME")
            ?: System.getProperty("java.home")
        mainClass = "com.najdev.snapvault.MainKt"
        jvmArgs += listOf(
            "-Dsun.java2d.dpiaware=true",
            "-Dhidpi=true",
            "-Dapple.awt.application.appearance=system"
        )
        // No keep rules are tuned for this dependency graph (Okio, kotlinx-serialization,
        // coroutines, Ktor), and the default ProGuard pass corrupts merged Okio bytecode —
        // Okio__OkioKt.buffer() ends up declared to return RealBufferedSource while its
        // body returns the wider BufferedSource, which the JVM verifier rejects at class-load
        // time (VerifyError, not a normal exception — silently killed every release run with
        // no log line before pipeline failures started being caught as Throwable). This is a
        // personal utility, not something that needs code-size minification or obfuscation.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "SnapVault"
            packageVersion = packageSemVer
            description = "Offline Snapchat Memories Downloader, Metadata Restorer, and Library Viewer"
            vendor = "NajDev"
            licenseFile.set(rootProject.file("LICENSE"))
            // jlink's module trimming is static-bytecode-only (jdeps), so it can't see the
            // modules Skiko/Netty/etc. reach via reflection or JNI at runtime. Without these,
            // the packaged binary aborts on launch with "pure virtual method called" — a
            // native crash, not a JVM exception, from Skiko's native init failing to find a
            // module it needs (jdk.unsupported / sun.misc.Unsafe-class APIs especially).
            // Confirmed via `./gradlew :composeApp:suggestRuntimeModules`.
            modules("java.instrument", "java.management", "java.prefs", "jdk.unsupported")
            macOS {
                bundleID = "com.najdev.snapvault"
                iconFile.set(project.file("src/desktopMain/resources/AppIcon.icns"))
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/AppIcon.ico"))
                // Fixed UUID for com.najdev.snapvault — must never change across versions so
                // Windows Installer upgrades existing installations in place instead of
                // leaving side-by-side copies in Add/Remove Programs.
                upgradeUuid = "9f639140-8696-5e27-b687-736738e04e32"
                perUserInstall = true
                dirChooser = true
                menuGroup = "SnapVault"
                shortcut = true
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/AppIcon.png"))
                debMaintainer = "support@naj-dev.com"
                menuGroup = "Utility"
                appCategory = "Graphics"
                shortcut = true
            }
        }
    }
}

// Debug mode (2,500-item import cap, [DEBUG] log lines) is OFF unless explicitly requested:
// packaged builds must never ship the cap because of how a task happened to be named.
// Opt in with -PisDebug=true; the desktop dev `run` task implies it.
val isDebugBuild: Boolean =
    (project.findProperty("isDebug") as? String)?.toBoolean()
        ?: gradle.startParameter.taskNames.any { it == "run" || it.endsWith(":run") }

// ShippedToolManifestTest reads the runtime fetch scripts, which are not compiled or packaged.
// Without declaring them, Gradle treats the suite as up to date after a script edit and never
// re-runs the check that the scripts still verify what the manifest records.
tasks.named<Test>("desktopTest") {
    inputs.dir(rootProject.file("scripts")).withPropertyName("runtimeFetchScripts")
    // Same reason as scripts above: CommitMsgHookTest runs the tracked hook as a process, so
    // without this an edit to the hook leaves the task up-to-date and the test passes on the
    // previous run's result — a broken hook reported as a working one.
    inputs.dir(rootProject.file(".githooks")).withPropertyName("gitHooks")
}

// Each OS/arch installer only needs its own `bin/<platform>/` archives (~0–48 MB), not all
// ~115 MB of foreign-OS executables committed under `src/desktopMain/resources/bin/`.
// However, `:composeApp:desktopTest` intentionally verifies every committed archive on any
// runner (`ShippedToolBundlesTest`, `ShippedToolManifestTest`), so filtering activates only
// when `-PbundleTargetPlatform=<platform>` is supplied (as in release.yml) or when a native
// distribution task runs without test tasks in the same Gradle invocation.
val allBundledBinPlatforms = listOf("darwin-arm64", "darwin-x64", "windows-x64", "linux-x64")

fun detectHostBinPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") ->
            if (arch.contains("aarch64") || arch.contains("arm64")) "darwin-arm64" else "darwin-x64"
        os.contains("win") -> "windows-x64"
        else -> "linux-x64"
    }
}

val requestedTasks = gradle.startParameter.taskNames
val isPackagingInvocation = requestedTasks.any { task ->
    val leaf = task.substringAfterLast(':')
    leaf.startsWith("package") || leaf.contains("Distributable")
}
val isTestInvocation = requestedTasks.any { task ->
    val leaf = task.substringAfterLast(':')
    leaf == "check" || leaf.contains("Test", ignoreCase = true)
}

val bundleTargetPlatform: String? = (project.findProperty("bundleTargetPlatform") as? String)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.also { explicit ->
        require(explicit in allBundledBinPlatforms) {
            "Unknown -PbundleTargetPlatform='$explicit' (expected one of $allBundledBinPlatforms)"
        }
    }
    ?: if (isPackagingInvocation && !isTestInvocation) detectHostBinPlatform() else null

// Ship the third-party license notices inside the app (classpath root), so every
// installer carries the ExifTool/FFmpeg license information it is required to include.
tasks.named<ProcessResources>("desktopProcessResources") {
    inputs.property("bundleTargetPlatform", bundleTargetPlatform ?: "all")
    from(rootProject.file("THIRD_PARTY_LICENSES.md"))
    if (bundleTargetPlatform != null) {
        allBundledBinPlatforms
            .filter { it != bundleTargetPlatform }
            .forEach { otherPlatform -> exclude("bin/$otherPlatform/**") }
    }
}

val generateBuildConfig by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/buildConfig/commonMain/kotlin")
    outputs.dir(outDir)
    inputs.property("version", appVersion)
    inputs.property("isDebug", isDebugBuild)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        // Deliberately `val`, not `const val`. IS_DEBUG changes whenever you alternate
        // between `run` (true) and compile/test (false), and a const is inlined at every
        // use site — so a const made every consumer of this file recompile on each switch,
        // which is the condition that leaves build/classes holding synthetic lambda classes
        // from two different compilations. As a plain val, only this file recompiles.
        dir.resolve("AppBuildConfig.kt").writeText("""
            package com.najdev.snapvault
            object AppBuildConfig {
                const val VERSION = "$appVersion"
                val IS_DEBUG = $isDebugBuild
            }
        """.trimIndent())
    }
}

kotlin.sourceSets.getByName("commonMain").kotlin
    .srcDir(generateBuildConfig.map { it.outputs.files })

// Point git at the tracked hooks directory.
//
// .git/hooks is not version controlled, so a hook living there protects one working copy and
// no one else's — and a fresh clone silently loses it. The hooks are committed under
// .githooks instead, and this repoints core.hooksPath at them. Wired into the desktop compile
// so a clone picks it up on the first build rather than on remembering to read the README.
//
// Idempotent, and a no-op outside a git checkout (a source tarball has no .git).
val installGitHooks by tasks.registering {
    group = "build setup"
    description = "Point core.hooksPath at the tracked .githooks directory"
    val hooksDir = rootProject.file(".githooks")
    val gitDir = rootProject.file(".git")
    onlyIf { gitDir.exists() && hooksDir.isDirectory }
    doLast {
        val current = runCatching {
            providers.exec {
                commandLine("git", "config", "--get", "core.hooksPath")
                isIgnoreExitValue = true
            }.standardOutput.asText.get().trim()
        }.getOrDefault("")

        if (current != ".githooks") {
            providers.exec {
                commandLine("git", "config", "core.hooksPath", ".githooks")
            }.result.get()
            logger.lifecycle("git hooks: core.hooksPath -> .githooks")
        }

        // git does not honour a hook that is not executable, and the permission bit can be
        // lost on checkout on some filesystems.
        hooksDir.listFiles()?.forEach { it.setExecutable(true) }
    }
}

tasks.named("compileKotlinDesktop") { dependsOn(installGitHooks) }

// Install the debug APK on a connected device/emulator, then launch the app.
// Prerequisites: adb on PATH, device connected or emulator running.
tasks.register<Exec>("runAndroid") {
    group = "application"
    description = "Build, install, and launch SnapVault on a connected Android device or emulator"
    dependsOn("installDebug")
    commandLine("adb", "shell", "am", "start", "-n", "com.najdev.snapvault/.MainActivity")
}

// Build the iOS app via xcodebuild, boot a simulator if needed, install and launch.
// Prerequisites: Xcode installed, simulator available.
tasks.register<Exec>("runIosSimulator") {
    group = "application"
    description = "Build and run SnapVault in the iOS Simulator (requires Xcode)"
    workingDir(rootProject.projectDir)
    commandLine("bash", "-c", """
        set -e

        DERIVED_DATA="iosApp/build"
        PROJECT="iosApp/iosApp.xcodeproj"
        SCHEME="iosApp"
        BUNDLE_ID="com.najdev.snapvault.ios"

        echo "==> Building iOS app (Debug, iphonesimulator)..."
        xcodebuild \
            -project "${'$'}PROJECT" \
            -scheme "${'$'}SCHEME" \
            -configuration Debug \
            -sdk iphonesimulator \
            -derivedDataPath "${'$'}DERIVED_DATA" \
            -destination "generic/platform=iOS Simulator" \
            | grep -E "^(Build|error:|warning: |CompileSwift|Ld |Linking|note:)" || true

        APP="${'$'}DERIVED_DATA/Build/Products/Debug-iphonesimulator/iosApp.app"

        echo "==> Finding or booting a simulator..."
        BOOTED=${'$'}(xcrun simctl list devices | grep " (Booted)" | grep -oE "[A-F0-9-]{36}" | head -1 || true)
        if [ -z "${'$'}BOOTED" ]; then
            BOOTED=${'$'}(xcrun simctl list devices available | grep "iPhone" | grep -oE "[A-F0-9-]{36}" | head -1 || true)
            if [ -z "${'$'}BOOTED" ]; then
                echo "ERROR: No iPhone simulator found. Open Xcode > Preferences > Platforms and download one." >&2
                exit 1
            fi
            echo "  Booting simulator ${'$'}BOOTED..."
            xcrun simctl boot "${'$'}BOOTED"
            open -a Simulator
            sleep 3
        fi

        echo "==> Installing app..."
        xcrun simctl install "${'$'}BOOTED" "${'$'}APP"
        echo "==> Launching app..."
        xcrun simctl launch "${'$'}BOOTED" "${'$'}BUNDLE_ID"
        echo "==> Done. Check the Simulator window."
    """.trimIndent())
}

// Native simulator tests read deterministic media fixtures from the host checkout.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    inputs.dir(project.file("src/iosTest/resources")).withPropertyName("nativeMediaFixtures")
    environment("SIMCTL_CHILD_SNAPVAULT_TEST_RESOURCES", project.file("src/iosTest/resources").absolutePath)
    environment("SIMCTL_CHILD_SNAPVAULT_TEST_OUTPUTS", layout.buildDirectory.dir("native-test-artifacts").get().asFile.absolutePath)
}
