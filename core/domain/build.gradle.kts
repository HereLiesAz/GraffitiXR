plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.hereliesaz.graffitixr.domain"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // CRITICAL FIX: Import core:common so Domain can see GraffitiProject
    implementation(project(":core:common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
