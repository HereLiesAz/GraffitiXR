pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "GraffitiXR"
include(":app")
include(":core:common", ":core:domain", ":core:data", ":core:nativebridge", ":core:design")
include(":feature:ar", ":feature:editor", ":feature:dashboard")

include(":opencv")
project(":opencv").projectDir = file("core/nativebridge/libs/opencv/sdk")