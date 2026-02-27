buildscript {
    val commonForcedDependencies = listOf(
        "commons-beanutils:commons-beanutils:1.11.0",
        "org.jdom:jdom2:2.0.6.1",
        "io.netty:netty-codec:4.1.131.Final",
        "io.netty:netty-codec-http2:4.1.131.Final",
        "io.netty:netty-handler:4.1.131.Final",
        "org.bitbucket.b_c:jose4j:0.9.6",
        "org.apache.commons:commons-lang3:3.20.0",
        "org.apache.httpcomponents:httpclient:4.5.14",
        "com.google.guava:guava:33.4.0-jre"
    )
    val protobufModules = listOf(
        "com.google.protobuf:protobuf-java",
        "com.google.protobuf:protobuf-javalite",
        "com.google.protobuf:protobuf-kotlin",
        "com.google.protobuf:protobuf-kotlin-lite"
    )
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // No direct dependencies here usually if using plugins block, but we need to configure resolutionStrategy
    }
    configurations.all {
        resolutionStrategy {
            // Force common dependencies
            commonForcedDependencies.forEach { force(it) }
            // AGP 9.0.1 requires Protobuf 3.25.5 in the buildscript classpath
            protobufModules.forEach { force("$it:3.25.5") }
        }
    }
}

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

// Define common forced dependencies again for application/library scope
val commonForcedDependencies = listOf(
    "commons-beanutils:commons-beanutils:1.11.0",
    "org.jdom:jdom2:2.0.6.1",
    "io.netty:netty-codec:4.1.131.Final",
    "io.netty:netty-codec-http2:4.1.131.Final",
    "io.netty:netty-handler:4.1.131.Final",
    "org.bitbucket.b_c:jose4j:0.9.6",
    "org.apache.commons:commons-lang3:3.20.0",
    "org.apache.httpcomponents:httpclient:4.5.14",
    "com.google.guava:guava:33.4.0-jre"
)

val protobufModules = listOf(
    "com.google.protobuf:protobuf-java",
    "com.google.protobuf:protobuf-javalite",
    "com.google.protobuf:protobuf-kotlin",
    "com.google.protobuf:protobuf-kotlin-lite"
)

allprojects {
    apply(plugin = "checkstyle")
    configure<CheckstyleExtension> {
        toolVersion = "10.12.0"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    }

    configurations.all {
        resolutionStrategy {
            // Force common dependencies
            commonForcedDependencies.forEach { force(it) }
            // Force Protobuf 4.28.2 for application runtime security
            protobufModules.forEach { force("$it:4.28.2") }
        }
    }
}
