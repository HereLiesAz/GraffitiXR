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

    // Align Java + Kotlin JVM targets at 17 (this module previously set neither,
    // leaving Kotlin on its default target while the rest of the project is on 17).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
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
