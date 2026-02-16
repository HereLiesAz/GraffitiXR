import org.gradle.api.plugins.quality.CheckstyleExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    // REMOVED: kotlin-android application
    alias(libs.plugins.kotlinx.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
}

subprojects {
    configurations.all {
        exclude(group = "com.github.HereLiesAz", module = "aznavrail-annotation")
    }
}

allprojects {
    apply(plugin = "checkstyle")
    configure<CheckstyleExtension> {
        toolVersion = "10.12.0"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    }
}

subprojects {
    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(module("com.github.HereLiesAz:aznavrail-annotation"))
                .using(module("com.github.HereLiesAz.AzNavRail:aznavrail-annotation:7.0"))
        }
    }
}
