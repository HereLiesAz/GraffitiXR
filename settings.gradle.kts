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
            dirs("libs")                   // For MLKit, LiteRT
            dirs("libs/opencv/native/jni") // For OpenCV
        }
    }
}

rootProject.name = "GraffitiXR"
include(":app")

val opencvSdk = file("libs/opencv")
// Check for build.gradle to ensure it's a valid project, preventing errors on empty dirs
if (file("libs/opencv/build.gradle").exists()) {
    include(":opencv")
    project(":opencv").projectDir = opencvSdk
}