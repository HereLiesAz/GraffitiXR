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
     // For MLKit, LiteRT
            dirs("app/libs/opencv/sdk/java")        // For OpenCV (Adjusted path)
        }
    }
}

rootProject.name = "GraffitiXR"
include(":app")

val opencvSdk = file("app/libs/opencv/sdk")
if (opencvSdk.exists()) {
    include(":opencv")
    project(":opencv").projectDir = opencvSdk
}