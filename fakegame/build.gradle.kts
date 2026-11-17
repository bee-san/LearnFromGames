// A stand-in for a visual novel, for testing LFG on an emulator or a handheld
// without a real game: a dialogue box whose text types itself out and advances
// on a tap or a button. Not part of the app.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.beesan.lfg.fakegame"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.beesan.lfg.fakegame"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.configureEach {
        java.srcDir("src/$name/kotlin")
    }
}

kotlin {
    jvmToolchain(17)
}
