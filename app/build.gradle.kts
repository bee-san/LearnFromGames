import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// A release is one APK per kind of device, so a handheld downloads only its own native code.
// Debug builds stay one APK that runs on both a handheld and an emulator.
val releasing = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

// The release key is the builder's own and is never in git: keystore.properties beside this
// project, or the same four values in the environment (which is how the release workflow gives them).
val keystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}
fun signing(name: String): String? = keystore.getProperty(name) ?: System.getenv("LFG_" + name.uppercase())

android {
    namespace = "io.github.beesan.lfg"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.beesan.lfg"
        // 13: the first release where the app can read how it was installed,
        // which decides whether the restricted-settings helper shows.
        minSdk = 33
        targetSdk = 35
        // The release workflow numbers each build and names it after its tag; a build at a desk is 0.1.0.
        versionCode = System.getenv("LFG_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("LFG_VERSION_NAME")?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "0.1.0"

        if (!releasing) {
            ndk {
                // Every handheld the plan targets is arm64; x86_64 is for emulators.
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // glaze, inside hoshidicts, needs CMake 3.31. The SDK's own packages stop
            // short of that, so a newer CMake on PATH is used.
            version = "3.31.0+"
        }
    }

    splits {
        abi {
            isEnable = releasing
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            signing("storeFile")?.let { storeFile = rootProject.file(it) }
            storePassword = signing("storePassword")
            keyAlias = signing("keyAlias")
            keyPassword = signing("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release key the APK is signed with the debug key, so it still installs for a
            // try-out. It cannot update, or be updated by, a properly signed release.
            signingConfig = if (signing("storeFile") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // AnkiDroid's API artifact ships AnkiDroid's own house rules (its collection
        // clock), which do not apply outside AnkiDroid.
        disable += "DirectSystemCurrentTimeMillisUsage"
        disable += "DirectDateInstantiation"
    }

    buildFeatures {
        compose = true
    }

    sourceSets.configureEach {
        java.srcDir("src/$name/kotlin")
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.ankidroid.api)

    testImplementation(libs.junit)
    // The same reader runs on the build machine against the real models.
    testImplementation(libs.onnxruntime.jvm)
}
