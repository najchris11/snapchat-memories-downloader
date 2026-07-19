plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.najdev.snapvault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.najdev.snapvault"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = (project.findProperty("app.version") as? String) ?: "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("com.squareup.okio:okio:3.17.0")
}

// Install the debug APK on a connected device/emulator, then launch the app.
// Prerequisites: adb on PATH, device connected or emulator running.
tasks.register<Exec>("runAndroid") {
    group = "application"
    description = "Build, install, and launch SnapVault on a connected Android device or emulator"
    dependsOn("installDebug")
    commandLine("adb", "shell", "am", "start", "-n", "com.najdev.snapvault/.MainActivity")
}
