// FILE: build.gradle.kts
import org.gradle.api.plugins.quality.CheckstyleExtension
buildscript {
    val commonForcedDependencies = listOf(
        "commons-beanutils:commons-beanutils:1.11.0",
        "org.jdom:jdom2:2.0.6.1",
        "io.netty:netty-codec:4.2.15.Final",
        "io.netty:netty-codec-http2:4.2.15.Final",
        "io.netty:netty-handler:4.2.15.Final",
        "io.netty:netty-handler-proxy:4.2.15.Final",
        "org.bitbucket.b_c:jose4j:0.9.6",
        "org.apache.commons:commons-lang3:3.20.0",
        "org.apache.httpcomponents:httpclient:4.5.14",
        "com.google.guava:guava:33.6.0-jre",
        "com.google.android.gms:play-services-basement:18.10.0",
        // Bouncy Castle: 1.79 (transitive, via the build + app classpaths) is vulnerable to
        // a covert timing channel (HIGH), LDAP injection, and a risky-crypto-algo issue in
        // bcpkix — all first patched in 1.84. The bcprov/bcpkix/bcutil versions must match.
        "org.bouncycastle:bcprov-jdk18on:1.84",
        "org.bouncycastle:bcpkix-jdk18on:1.84",
        "org.bouncycastle:bcutil-jdk18on:1.84",
        // Kotlin 2.4.0 emits class metadata version 2.4.0, but Hilt/Dagger 2.59.2 bundles a
        // kotlin-metadata-jvm that only reads up to 2.3.0 — its KSP processor fails the build with
        // "Provided Metadata instance has version 2.4.0, while maximum supported version is 2.3.0".
        // Force the matching 2.4.0 reader onto every classpath (incl. the KSP processor) so Hilt
        // can parse 2.4.0 metadata. Keep this version in lockstep with `kotlin` in libs.versions.toml.
        "org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0"
    )

    val protobufModules = listOf(
        "com.google.protobuf:protobuf-java",
        "com.google.protobuf:protobuf-javalite",
        "com.google.protobuf:protobuf-kotlin",
        "com.google.protobuf:protobuf-kotlin-lite"
    )

    // Protobuf is intentionally pinned to TWO different versions: the buildscript classpath needs
    // 3.25.5 for the current AGP (see gradle/libs.versions.toml), while the application runtime
    // needs the 4.x line for security fixes.
    // These are named (not inline literals) so the split is explicit in one place and a future edit
    // can't silently collide them. See the resolutionStrategy blocks below and in allprojects.
    val protobufBuildscriptVersion = "3.25.5"
    val protobufRuntimeVersion = "4.28.2"

    // Store in extra properties so allprojects can access it
    extra["commonForcedDependencies"] = commonForcedDependencies
    extra["protobufModules"] = protobufModules
    extra["protobufBuildscriptVersion"] = protobufBuildscriptVersion
    extra["protobufRuntimeVersion"] = protobufRuntimeVersion

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
            // The current AGP requires Protobuf 3.25.5 in the buildscript classpath
            protobufModules.forEach { force("$it:$protobufBuildscriptVersion") }
        }
    }
}


plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.jetbrains.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

allprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    detekt {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    }

    @Suppress("UNCHECKED_CAST")
    val commonForcedDependencies = rootProject.extra["commonForcedDependencies"] as List<String>
    @Suppress("UNCHECKED_CAST")
    val protobufModules = rootProject.extra["protobufModules"] as List<String>
    val protobufRuntimeVersion = rootProject.extra["protobufRuntimeVersion"] as String

    // Security invariant: the app runtime must stay on the 4.x protobuf line (the buildscript's
    // 3.25.x is for AGP only). Fail fast if a future edit drops it back to 3.x.
    check(protobufRuntimeVersion.startsWith("4.")) {
        "Application runtime protobuf must remain on the 4.x line (was $protobufRuntimeVersion)"
    }

    configurations.all {
        resolutionStrategy {
            // Force common dependencies
            commonForcedDependencies.forEach { force(it) }
            // Force Protobuf 4.x for application runtime security
            protobufModules.forEach { force("$it:$protobufRuntimeVersion") }
        }
    }
}