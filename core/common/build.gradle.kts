plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    alias(libs.plugins.jetbrains.kotlin.compose) // Required for @Parcelize in Parcelers.kt
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.hereliesaz.graffitixr.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        compose = true // Required for Offset/Color in Parcelers.kt
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    // MISSING DEPENDENCIES RESTORED:
    implementation(libs.play.services.location.v2101) // For LocationTracker

    // Compose Dependencies (for Parcelers.kt, ProgressCalculator.kt)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui.ui3)
    implementation(libs.androidx.compose.ui.ui.graphics)
    implementation(libs.androidx.compose.ui.ui.tooling.preview2)
    implementation(libs.androidx.compose.material3.material33)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.opencv)
}
