plugins {
    id("com.android.library")
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.hereliesaz.graffitixr.core.collaboration"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Align the Java JVM target at 17 (this module previously set neither Java nor Kotlin
    // targets, leaving both on their defaults while the rest of the project is on 17).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.cbor)
    implementation("javax.inject:javax.inject:1")
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

// Pin Kotlin's JVM target to match Java (17). Uses the same task-based approach as :app
// (this module applies only AGP + the serialization compiler plugin), avoiding reliance on
// the `kotlin {}` extension.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
