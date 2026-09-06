import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlinx.serialization) // Required for @Serializable
}

android {
    namespace = "com.hereliesaz.graffitixr.common"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No proguard-rules.pro exists in this module (only consumer-rules.pro, wired above via
            // consumerProguardFiles) — a proguardFiles() reference to it was dangling and, since this
            // library is never minified standalone, had never actually been exercised.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Location (Fixes Unresolved reference 'FusedLocationProviderClient')
    implementation(libs.play.services.location)

    // Provider Installer (Fixes SSLHandshakeException)
    implementation(libs.play.services.base)

    // OpenCV (Fixes Unresolved reference 'opencv', 'Mat', 'Imgproc') — imported from Maven Central,
    // not vendored. `api` so downstream modules (data, feature:*) see org.opencv.* transitively.
    api(libs.opencv)

    // AzNavRail (Fixes NoClassDefFoundError for AzOrientation)
    api(libs.az.nav.rail)

    // Serialization (Fixes Unresolved reference 'serializer')
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)

    // Networking (Crash Reporting)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Logging
    implementation(libs.timber)

    // Hilt / DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}