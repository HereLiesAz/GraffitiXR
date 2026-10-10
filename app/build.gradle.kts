// FILE: app/build.gradle.kts
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlinx.serialization)
}

// Load version properties
val versionPropsFile = project.rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}

// Load local properties
val localProperties = Properties().apply {
    val localPropertiesFile = project.rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

// Version resolution. On EVERY compile (any build type, any machine, any Gradle task that will
// actually compile bytecode) both the build number and the patch are incremented:
//   - versionBuild  -> the Android versionCode. Monotonic; NEVER resets.
//   - versionPatch  -> the patch segment of the versionName. Increments each compile, but resets to
//                      0 when versionMinor was bumped since the last build (a new minor starts at .0).
//                      versionMinorLast tracks the minor we last built so that reset is automatic.
// True when the requested tasks will trigger real compilation — not a sync, `tasks`, `clean`,
// a `--dry-run`, or a diagnostic like `buildEnvironment`/`buildHealth`. Build verbs cover every
// entry point that transitively invokes a KotlinCompile / JavaCompile task on this project: the
// full android build lifecycle (assemble/bundle/install/package), explicit compile invocations,
// unit-test / instrumented-test / verification tasks (test/check/lint/verify/connectedTest — all
// depend on compileDebugKotlin / compileReleaseKotlin), and `run` for library modules. Verbs are
// matched as a prefix on the leaf task name and the `build` lifecycle task is matched exactly, so
// diagnostics that merely contain "build" don't trip it.
val startParameter = gradle.startParameter
val buildVerbs = listOf(
    "assemble", "bundle", "install", "package", "compile",
    "test", "check", "lint", "verify", "connected", "run",
)
val isBuilding = !startParameter.isDryRun && startParameter.taskNames.any { taskName ->
    val task = taskName.substringAfterLast(':').lowercase()
    task == "build" || buildVerbs.any { task.startsWith(it) }
}

val verMajor = versionProps.getProperty("versionMajor", "1")
val verMinor = versionProps.getProperty("versionMinor", "0")
// Detect a minor bump BEFORE the build-gated block so the reset also applies to CI/override builds
// (and IDE syncs), where the block is skipped: a new minor always reads as patch 0 even if the file
// still holds the previous minor's patch (it may not have been rewritten by a local build yet).
val lastMinor = versionProps.getProperty("versionMinorLast", verMinor)
val isMinorBumped = verMinor != lastMinor

var currentVersionCode = versionProps.getProperty("versionBuild", "1").toInt()
var currentPatch = if (isMinorBumped) 0 else versionProps.getProperty("versionPatch", "0").toInt()

// CI supplies its own versionCode (derived from the run number) and never rewrites the tracked
// version.properties: the same commit then builds the same code however many compiles ran first,
// and two branches' CI builds can't publish identical codes. Local builds keep auto-incrementing.
val ciVersionCode = System.getenv("CI_VERSION_CODE")?.toIntOrNull()?.takeIf { it > 0 }
if (ciVersionCode != null) {
    currentVersionCode = ciVersionCode
    // Keep versionName distinct per CI build too: without this the patch stays frozen at the tracked
    // value and every Play release reports the same human-readable version across many versionCodes.
    // The CI code is monotonic (1_000_000 + run_number*10 + attempt), so it makes a unique patch.
    currentPatch = ciVersionCode
} else if (isBuilding) {
    currentVersionCode++ // build never resets
    // A minor bump makes this build the new minor's .0; otherwise advance the patch.
    if (!isMinorBumped) currentPatch++

    versionProps.setProperty("versionBuild", currentVersionCode.toString())
    versionProps.setProperty("versionPatch", currentPatch.toString())
    versionProps.setProperty("versionMinorLast", verMinor)
    versionPropsFile.outputStream().use {
        versionProps.store(it, "Auto-incremented on compile")
    }
}

val currentVersionName = "$verMajor.$verMinor.$currentPatch"

android {
    namespace = "com.hereliesaz.graffitixr"
    compileSdk = 37

    // Store ONNX models uncompressed in the APK. Leaving them gz-packed slows load and breaks
    // AssetManager.openFd(), and ONNX Runtime memory-maps the weights, which needs them uncompressed
    // on disk anyway. (The depth model is now MiDaS Small int8 — a single ~17 MB self-contained graph.)
    androidResources {
        noCompress += listOf("onnx")
    }

    defaultConfig {
        applicationId = "com.hereliesaz.graffitixr"
        minSdk = 26
        targetSdk = 37
        versionCode = currentVersionCode
        versionName = currentVersionName

        // Non-ARCore (artoolkitX) smoke test: build with -PposeProbe=true to run the non-ARCore pose
        // source at app launch and log poses to logcat (tag POSEPROBE), independent of the ARCore-gated
        // AR mode. Default false — zero effect on normal builds.
        buildConfigField(
            "boolean",
            "POSE_FORCE_PROBE",
            ((project.findProperty("poseProbe") as String?)?.toBoolean() ?: false).toString(),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // ARM only, matching :core:nativebridge. Keeps the OpenCV Prefab prebuilts (which ship
            // all four ABIs) from packaging x86 .so files that would have no matching libgraffitixr.so.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Crash auto-reporting (opt-in) is credentialed by a token the artist enters in Settings and
        // that lives only in the app's DataStore (see SettingsRepository.crashReportToken) — NEVER
        // compiled into the APK. The old BuildConfig.CRASH_REPORT_TOKEN baked a live GitHub credential
        // into every published binary, where decompiling it took minutes; that whole path is gone.
    }

    // Release signing is a property of the project, not of each CI invocation. The keystore and
    // credentials come from the environment: CI decodes the base64 `KEYSTORE_RAW` secret to
    // app/keystore.jks and exports KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD (see
    // .github/workflows/release.yml, the only workflow that signs). KEYSTORE_FILE may override the path.
    //
    // When no keystore is present (local dev without the secrets) the "release" config is simply
    // not created — `findByName` then returns null below, so release builds stay unsigned and debug
    // builds keep the default debug key. Nothing breaks, and no plaintext credentials live in Git.
    val envKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val envKeyAlias = System.getenv("KEY_ALIAS")
    val envKeyPassword = System.getenv("KEY_PASSWORD")
    // Resolve KEYSTORE_FILE against the repo root so a relative CI path can't become app/app/...;
    // default to this module's keystore.jks. Enable signing only when the keystore AND all three
    // credentials are present, so a stray local keystore without env credentials still falls back
    // gracefully (configuration succeeds; the build doesn't blow up at execution on missing creds).
    val releaseKeystore = (System.getenv("KEYSTORE_FILE")?.let { rootProject.file(it) } ?: file("keystore.jks"))
        .takeIf {
            it.exists() &&
                !envKeystorePassword.isNullOrEmpty() &&
                !envKeyAlias.isNullOrEmpty() &&
                !envKeyPassword.isNullOrEmpty()
        }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Null (unsigned) only when no keystore was supplied — e.g. a local build without secrets.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // Always the default debug key. A debuggable APK signed with the release key can be
            // installed over a real release, and `run-as` then exposes the app's private data, so
            // the production key never signs debug builds (including the CI-published one).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21

    }

    buildFeatures {
        compose = true
        buildConfig = true
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    // App Bundle configuration. These splits are ON by default for an AAB; we set
    // them explicitly so the modular-delivery intent is documented in the build.
    // Play generates optimized per-device APKs from `bundleRelease`, so the large
    // per-ABI native payload (:core:nativebridge / OpenCV / LiteRT NPU runtimes)
    // is only downloaded for the device's actual ABI — no separate artifacts to
    // build. See docs/RELEASE.md.
    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
    }
}


tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val version = variant.outputs.first().versionName.get()
            val code = variant.outputs.first().versionCode.get()
            val apkName = "GraffitiXR-${variant.name}-$version.$code.apk"
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(apkName)
        }
    }
}

dependencies {
    implementation(project(":android_collaboration_module"))
    implementation(project(":core:nativebridge"))
    implementation(project(":feature:ar"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:dashboard"))
    implementation(project(":core:common"))
    implementation(project(":core:design"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)

    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.timber)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.play.services.base)

    implementation(libs.az.nav.rail)
    implementation(libs.coil.compose)
    implementation(libs.compose.ui.text.google.fonts)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
}
