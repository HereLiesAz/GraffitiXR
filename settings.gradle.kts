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

val opencvSdk = file("app/libs/opencv/sdk")
// Check for build.gradle to ensure it's a valid project, preventing errors on empty dirs
if (file("app/libs/opencv/sdk/build.gradle").exists()) {
    include(":opencv")
    project(":opencv").projectDir = opencvSdk
}