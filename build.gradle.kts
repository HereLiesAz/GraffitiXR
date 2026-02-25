buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // No direct dependencies here usually if using plugins block, but we need to configure resolutionStrategy
    }
    configurations.all {
        resolutionStrategy {
            force("commons-beanutils:commons-beanutils:1.11.0")
            // Protobuf 3.25.5 is the patched version compatible with AGP 9.0.1 (which uses 3.x)
            force("com.google.protobuf:protobuf-java:3.25.5")
            force("com.google.protobuf:protobuf-javalite:3.25.5")
            force("com.google.protobuf:protobuf-kotlin:3.25.5")
            force("com.google.protobuf:protobuf-kotlin-lite:3.25.5")
            force("org.jdom:jdom2:2.0.6.1")
            force("io.netty:netty-codec-http2:4.1.124.Final")
            force("io.netty:netty-handler:4.1.124.Final")
            force("org.bitbucket.b_c:jose4j:0.9.6")
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

allprojects {
    apply(plugin = "checkstyle")
    configure<CheckstyleExtension> {
        toolVersion = "10.12.0"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    }

    configurations.all {
        resolutionStrategy {
            force("commons-beanutils:commons-beanutils:1.11.0")
            // For app dependencies, we can use the latest patched version 4.28.2
            force("com.google.protobuf:protobuf-java:4.28.2")
            force("com.google.protobuf:protobuf-javalite:4.28.2")
            force("com.google.protobuf:protobuf-kotlin:4.28.2")
            force("com.google.protobuf:protobuf-kotlin-lite:4.28.2")
            force("org.jdom:jdom2:2.0.6.1")
            force("io.netty:netty-codec-http2:4.1.124.Final")
            force("io.netty:netty-handler:4.1.124.Final")
            force("org.bitbucket.b_c:jose4j:0.9.6")
        }
    }
}
