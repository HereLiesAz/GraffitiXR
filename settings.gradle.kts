pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack builds are mutable and unsigned, so it may serve ONLY our own AzNavRail artifacts;
        // every other dependency must come from Google or Maven Central.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.HereLiesAz(\\..*)?") }
        }
    }
}
rootProject.name = "GraffitiXR"
include(":app")
include(":core:common", ":core:domain", ":core:data", ":core:nativebridge", ":core:design")
include(":feature:ar", ":feature:editor", ":feature:dashboard")

include(":android_collaboration_module")
project(":android_collaboration_module").projectDir = file("collab")

// OpenCV (Java + native C++) is imported as a normal Maven Central dependency (org.opencv:opencv),
// not vendored. Since 4.9.0 that artifact ships a Prefab part, so `find_package(OpenCV)` in native
// code resolves against it too — nothing to host, fetch, or commit.

