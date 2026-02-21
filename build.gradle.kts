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

allprojects {
    apply(plugin = "checkstyle")
    configure<CheckstyleExtension> {
        toolVersion = "10.12.0"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    }

    // FIX: Removed global exclusion of aznavrail-annotation. 
    // This was preventing the library's annotations from being included, 
    // forcing the use of local (and incomplete) shadow files.
    configurations.all {
        // exclude(group = "com.github.HereLiesAz.AzNavRail", module = "aznavrail-annotation")
    }
}
