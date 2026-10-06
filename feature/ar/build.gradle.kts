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
        // The forked unit-test JVM defaults to ~512 MB (org.gradle.jvmargs sizes the daemon, not this
        // JVM). A 1 GB cushion keeps the run deterministic now that ONNX Runtime is on the classpath.
        // (The suite's earlier OOM was ArViewModel.init firing the monocular-depth self-test against a
        // relaxed-mock AssetManager at every VM construction; the AR tests now stub monocularDepthEnabled
        // off so the DepthEstimator/ORT path never runs. This heap is belt-and-suspenders.)
        unitTests.all {
            it.maxHeapSize = "1g"
        }
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
    // Side-by-side non-ARCore tracker. ARCore remains a separate first-class pose source.
    // SphereSLAM provides the base KPM tracking, world-size retention (AnchoredStandaloneSession /
    // OverlayPlacement), 2-D sweep coverage (SphereCoverage / CameraAttitudeProvider), and the glow
    // overlay; GraffitiXR's fingerprint (MobileGS) reloc and pose fusion layer on top.
    // `api`, not `implementation`: feature/ar's public surface exposes SphereSLAM types
    // (e.g. ArViewModel.sphereThinDirections -> SphereCoverage.Direction), so :app must see them.
    api("com.github.HereLiesAz.SphereSLAM:sphereslam:0.10.0")
    implementation("com.github.HereLiesAz.SphereSLAM:overlay:0.10.0") // drop-in coverage glow (GL)
    // Reloc robustness: the acceptance/stabilizer/state-machine/age policies (and the fused
    // RobustTrackingLoop) GraffitiXR used to carry locally, now owned by the library. The
    // proprietary fingerprint build + corroboration fusion layer on top of these.
    implementation("com.github.HereLiesAz.SphereSLAM:reloc:0.10.0")
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

    // ONNX Runtime (Kotlin) for monocular depth (MiDaS Small) on the non-ARCore path.
    // OpenCV DNN (the native engine's ONNX runtime) can't reliably import the ViT depth model,
    // so depth runs here, where its consumer (SphereSlamStandaloneTrackingAnalyzer) lives.
    implementation(libs.onnxruntime.android)

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
// `FootprintRegionWireTest` and `CorroborationTranslitTest` read these native sources as *text*.
// Both check agreements that no toolchain validates: the Footprint.Region ordinals are a wire
// format shared between Kotlin and C++, and the Phase-4 search radius and keypoint grid exist twice
// — a tested Kotlin reference and the C++ transliteration that actually runs on the device.
// Declaring the sources as inputs keeps the task from staying UP-TO-DATE when only the native side
// moves, which is precisely when the checks matter.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.file("core/nativebridge/src/main/cpp/include/MobileGS.h"),
        rootProject.file("core/nativebridge/src/main/cpp/MobileGS.cpp"),
        rootProject.file("core/nativebridge/src/main/cpp/include/SearchRadius.h"),
        rootProject.file("core/nativebridge/src/main/cpp/include/KeypointGrid.h"),
    ).withPropertyName("nativeSourcesForWireAndTranslitTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
