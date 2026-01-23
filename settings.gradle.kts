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
        flatDir {
            dirs("libs")                       // For MLKit, LiteRT
            dirs("libs/opencv/sdk/native/jni") // For OpenCV
        }
    }
}

rootProject.name = "GraffitiXR"
include(":app")

val opencvSdk = file("libs/opencv/sdk")
// Check for build.gradle to ensure it's a valid project
if (file("libs/opencv/sdk/build.gradle").exists()) {
    include(":opencv")
    project(":opencv").projectDir = opencvSdk
}