import org.jetbrains.compose.desktop.application.dsl.TargetFormat

val appVersion = (project.findProperty("app.version") as? String) ?: "0.0.0"

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
                implementation(compose.materialIconsExtended)
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
        mainClass = "com.najdev.snapvault.MainKt"
        jvmArgs += listOf(
            "-Dsun.java2d.dpiaware=true",
            "-Dhidpi=true",
            "-Dapple.awt.application.appearance=system"
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "SnapVault"
            packageVersion = "1.0.0"
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
