plugins {
    id("com.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlinx.serialization) // Required for @Serializable registry API models
}

android {
    namespace = "com.hereliesaz.graffitixr.data"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

// Pin Kotlin's JVM target to match Java (17). Without this, Kotlin defaults to a lower target
// than the Java sources, which AGP flags as an inconsistent JVM-target compatibility error.
// Uses the same task-based approach as :app (no `kotlin {}` extension, which this module's
// plugin set does not register).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(project(":core:common"))
    implementation(project(":core:domain"))

    implementation(libs.gson)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.compose.ui.geometry)
    // ProjectManager persists layer BlendMode (androidx.compose.ui.graphics.BlendMode). This was
    // previously reaching us transitively through core:common's api(az-nav-rail); the AzNavRail
    // Android artifact no longer leaks Compose, so declare the dependency we actually use.
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
