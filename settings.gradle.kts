pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // AnkiDroid publishes its API artifact only here.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.ankidroid") }
        }
    }
}

rootProject.name = "LearnFromGames"
include(":app")
include(":fakegame")
