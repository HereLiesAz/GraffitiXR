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
        maven { setUrl("https://jitpack.io") }

        // --- DYNAMIC LOCAL REPOSITORIES ---
        // These allow Gradle to find the AARs fetched by setup_libs
        flatDir {
            dirs("app/libs")                   // For MLKit, LiteRT
            dirs("app/libs/opencv/java")       // For OpenCV
        }
    }
}

rootProject.name = "GraffitiXR"
include(":app")