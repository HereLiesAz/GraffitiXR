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

// Version resolution. On EVERY compile (any build type, any machine) both the build number and the
// patch are incremented:
//   - versionBuild  -> the Android versionCode. Monotonic; NEVER resets.
//   - versionPatch  -> the patch segment of the versionName. Increments each compile, but resets to
//                      0 when versionMinor was bumped since the last build (a new minor starts at .0).
//                      versionMinorLast tracks the minor we last built so that reset is automatic.
// An explicit `-PversionBuild=<n>` override always wins (CI passes the incremented versionBuild read
// from version.properties — see release-aab.yml, which also commits that value back so it persists);
// when present we do NOT auto-increment or rewrite the file here, so the build uses exactly <n>.
val versionBuildOverride = project.findProperty("versionBuild")?.toString()?.toIntOrNull()
// Likewise `-PversionPatch=<n>` sets the versionName patch segment (CI passes version.properties'
// versionPatch — see release-aab.yml). When EITHER override is present the file is left untouched.
val versionPatchOverride = project.findProperty("versionPatch")?.toString()?.toIntOrNull()

// True when the requested tasks actually compile/assemble the app — not a sync, `tasks`, `clean`,
// a `--dry-run`, or a diagnostic like `buildEnvironment`/`buildHealth`. Build verbs are matched as a
// prefix and the `build` lifecycle task exactly, so diagnostics that merely contain "build" don't trip it.
val startParameter = gradle.startParameter
val buildVerbs = listOf("assemble", "bundle", "install", "package", "compile")
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

var currentVersionCode = versionBuildOverride ?: versionProps.getProperty("versionBuild", "1").toInt()
var currentPatch = versionPatchOverride ?: if (isMinorBumped) 0 else versionProps.getProperty("versionPatch", "0").toInt()

if (versionBuildOverride == null && versionPatchOverride == null && isBuilding) {
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

    defaultConfig {
        applicationId = "com.hereliesaz.graffitixr"
        minSdk = 26
        targetSdk = 37
        versionCode = currentVersionCode
        versionName = currentVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

        // Crash auto-reporting: CrashUploadWorker files a GitHub issue containing the crash log, using
        // this token. It is read at BUILD time from the GH_TOKEN env var (the same one
        // settings.gradle.kts uses for the GitHub Packages maven repo), with a gradle-property
        // fallback. When neither is present (typical local dev) it stays empty and CrashUploadWorker
        // no-ops — so nothing breaks locally and no token is ever committed to Git.
        //
        // SECURITY: a non-empty token here is embedded in the shipped APK's BuildConfig and CAN be
        // extracted by anyone who decompiles the app. Use a FINE-GRAINED token scoped to ONLY
        // "Issues: write" on this single repo (HereLiesAz/GraffitiXR) — never a broad/classic PAT —
        // so that a leaked token can, at worst, open issues on this one repo.
        // GitHub tokens are [A-Za-z0-9_] only (ghp_* / github_pat_*), so no string escaping is needed.
        val crashReportToken = System.getenv("GH_TOKEN")
            ?: (project.findProperty("GH_TOKEN") as String?)
            ?: ""
        buildConfigField("String", "GH_TOKEN", "\"$crashReportToken\"")
    }

    // Release signing is a property of the project, not of each CI invocation. The keystore and
    // credentials come from the environment: CI decodes the base64 `KEYSTORE_RAW` secret to
    // app/keystore.jks and exports KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD (see the
    // release-apk / release-aab / merged-build workflows). KEYSTORE_FILE may override the path.
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
            // The auto-published CI build (merged-build.yml) assembles the debug variant. Sign it with
            // the RELEASE key when available so its signature stays stable across builds (in-place
            // updates keep working); fall back to the default debug key for local development.
            signingConfig = signingConfigs.findByName("release") ?: signingConfig
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17

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
        jvmTarget.set(JvmTarget.JVM_17)
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
