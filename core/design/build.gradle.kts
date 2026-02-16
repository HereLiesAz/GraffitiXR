plugins {
    id("com.android.library")
    alias(libs.plugins.jetbrains.kotlin.compose)
}

android {
    namespace = "com.hereliesaz.graffitixr.design"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
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
    }
    ndkVersion = "28.2.13676358"

}

dependencies {
    implementation(project(":core:common"))

    // UI Frameworks
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // MISSING DEPENDENCY RESTORED:
    // Required for legacy vector drawables using ?attr/colorControlNormal
    implementation(libs.androidx.appcompat)
    
    // ADDED: Required for shared 3D rendering (ProjectedImageRenderer)
    implementation(libs.arcore.client)
}
