// Top-level settings file
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
        maven {
            setUrl("http://repo.eqgis.cn")
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "GraffitiXR"
include(":app")
