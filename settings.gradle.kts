pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

val localProperties = java.util.Properties().apply {
    val localPropertiesFile = rootDir.resolve("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "unused"
                password = (System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN") ?: 
                            localProperties.getProperty("GH_TOKEN") ?: localProperties.getProperty("github_token") ?: "").trim()
            }
        }
    }
}
rootProject.name = "GraffitiXR"
include(":app")
include(":core:common", ":core:domain", ":core:data", ":core:nativebridge", ":core:design")
include(":feature:ar", ":feature:editor", ":feature:dashboard")

include(":android_collaboration_module")
project(":android_collaboration_module").projectDir = file("collab")

include(":opencv")
project(":opencv").projectDir = file("core/nativebridge/libs/opencv/sdk")

