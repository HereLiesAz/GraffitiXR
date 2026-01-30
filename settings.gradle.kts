pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "GraffitiXR"
include(":app")
include(":core:common", ":core:domain", ":core:data", ":core:native", ":core:design")
include(":feature:ar", ":feature:editor", ":feature:dashboard")
