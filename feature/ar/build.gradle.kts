// FILE: feature/ar/build.gradle.kts
plugins {
    id("com.android.library")
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/**
 * Short git commit for the eval run-identity sidecar (EVALUATION.md 3.2).
 *
 * A CSV whose build is unknown cannot be compared against another run, so this is recorded rather
 * than inferred. Resolved at configure time and never allowed to fail the build: CI shallow clones,
 * source archives and worktrees can all leave git unusable, and "unknown" is an honest answer where
 * a crashed build is not.
 */
val gitCommitForEval: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.map { it.trim() }.orElse("unknown").getOrElse("unknown")
    .ifBlank { "unknown" }

android {
    namespace = "com.hereliesaz.graffitixr.feature.ar"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommitForEval\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true   // for GIT_COMMIT, above
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":core:design"))
    implementation(project(":core:data"))

    // Native Engine (MobileGS)
    implementation(project(":core:nativebridge"))
    implementation(project(":android_collaboration_module"))

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.arcore.client)
    implementation(libs.opencv)

    implementation(libs.az.nav.rail)
    implementation(libs.androidx.activity.compose)
    implementation(libs.navigation.compose)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Removed MLKit segmentation dependency here
    implementation(libs.kotlinx.coroutines.play.services)

    // Logging
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}