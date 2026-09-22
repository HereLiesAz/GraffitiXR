// FILE: build.gradle.kts
buildscript {
    val commonForcedDependencies = listOf(
        "commons-beanutils:commons-beanutils:1.11.0",
        "org.jdom:jdom2:2.0.6.1",
        "io.netty:netty-codec:4.2.18.Final",
        "io.netty:netty-codec-http2:4.2.18.Final",
        "io.netty:netty-handler:4.2.18.Final",
        "io.netty:netty-handler-proxy:4.2.18.Final",
        "org.bitbucket.b_c:jose4j:0.9.7",
        "org.apache.commons:commons-lang3:3.20.0",
        "org.apache.httpcomponents:httpclient:4.5.14",
        "com.google.guava:guava:33.7.1-jre",
        // play-services-base 18.10.1 (see libs.versions.toml) itself depends on
        // play-services-basement 18.11.0 (confirmed against its published POM) -- forcing
        // basement below that strips classes base's own obfuscated internals reference
        // (com.google.android.gms.common.internal.ConnectionThrottlingConfig,
        // InternalClientFlagRegistry, InternalClientFlags) off the classpath entirely,
        // which R8 then reports as missing rather than merely unused. 18.11.0 also still
        // satisfies play-services-location (needs 18.9.0) and the mlkit segmentation
        // artifact (needs 18.1.0), the two other basement consumers on this classpath.
        "com.google.android.gms:play-services-basement:18.11.0",
        // Bouncy Castle: 1.79 (transitive, via the build + app classpaths) is vulnerable to
        // a covert timing channel (HIGH), LDAP injection, and a risky-crypto-algo issue in
        // bcpkix — all first patched in 1.84. Forced here to 1.86 (a later release that still
        // carries those fixes); the bcprov/bcpkix/bcutil versions must match.
        "org.bouncycastle:bcprov-jdk18on:1.86",
        "org.bouncycastle:bcpkix-jdk18on:1.86",
        "org.bouncycastle:bcutil-jdk18on:1.86",
        // Kotlin (currently 2.4.20, see `kotlin` in libs.versions.toml) emits class metadata at
        // its own version, but Hilt/Dagger (currently 2.60.1) bundles a kotlin-metadata-jvm that
        // may only read up to an older metadata version — its KSP processor fails the build with
        // "Provided Metadata instance has version X, while maximum supported version is Y" when
        // the two are out of sync. Force the matching kotlin-metadata-jvm reader onto every
        // classpath (incl. the KSP processor) so Hilt can parse the current Kotlin's metadata.
        // Keep this version in lockstep with `kotlin` in libs.versions.toml. NOTE: this comment's
        // version numbers have been corrected to match current pins (Hilt 2.60.1 / Kotlin 2.4.20)
        // but whether the underlying incompatibility still reproduces at these versions has not
        // been re-verified — treat as a candidate to re-test before removing.
        "org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.20"
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