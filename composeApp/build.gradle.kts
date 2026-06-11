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
        iosSimulatorArm64(),
        iosX64()
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
            }
        }
        
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.13.0")
                implementation("androidx.appcompat:appcompat:1.7.1")
                implementation("androidx.core:core-ktx:1.18.0")
                implementation("androidx.exifinterface:exifinterface:1.4.2")
                implementation("io.ktor:ktor-client-okhttp:3.5.0")
            }
        }
        
        val desktopMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation(compose.desktop.currentOs)
                implementation(compose.foundation)
                implementation("io.ktor:ktor-client-cio:3.5.0")
            }
        }
        
        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.5.0")
            }
        }
        
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
        val iosX64Main by getting { dependsOn(iosMain) }
    }
}

android {
    namespace = "com.najdev.snapvault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.najdev.snapvault"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
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
            ?: "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
        mainClass = "com.najdev.snapvault.MainKt"
        jvmArgs += listOf(
            "-Dsun.java2d.dpiaware=true",
            "-Dhidpi=true",
            "-Dapple.awt.application.appearance=system"
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "SnapVault"
            packageVersion = packageSemVer
        }
    }
}

val generateBuildConfig by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/buildConfig/commonMain/kotlin")
    outputs.dir(outDir)
    inputs.property("version", appVersion)
    inputs.property("isDebug", gradle.startParameter.taskNames.none { "release" in it.lowercase() || "Dist" in it })
    doLast {
        val isDebug = gradle.startParameter.taskNames.none { "release" in it.lowercase() || "Dist" in it }
        val dir = outDir.get().asFile
        dir.mkdirs()
        dir.resolve("AppBuildConfig.kt").writeText("""
            package com.najdev.snapvault
            object AppBuildConfig {
                const val VERSION = "$appVersion"
                const val IS_DEBUG = $isDebug
            }
        """.trimIndent())
    }
}

kotlin.sourceSets.getByName("commonMain").kotlin
    .srcDir(generateBuildConfig.map { it.outputs.files })

tasks.register<JavaExec>("runCli") {
    group = "application"
    mainClass.set("com.najdev.snapvault.MainCliKt")
    workingDir = rootProject.projectDir
    classpath = kotlin.targets.getByName("desktop").compilations.getByName("main").output.allOutputs +
                configurations.getByName("desktopRuntimeClasspath")
}

// Install APK on a connected device/emulator then launch the app.
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
