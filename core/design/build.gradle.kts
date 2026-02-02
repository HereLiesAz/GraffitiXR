plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    alias(libs.plugins.jetbrains.kotlin.compose)
}

android {
    namespace = "com.hereliesaz.graffitixr.design"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        compose = true
    }


}

dependencies {
    implementation(project(":core:common"))

    // UI Frameworks
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui.ui2)
    implementation(libs.ui.graphics)
    implementation(libs.androidx.compose.material3.material32)
    implementation(libs.androidx.compose.material.icons.extended)

    // MISSING DEPENDENCY RESTORED:
    // Required for legacy vector drawables using ?attr/colorControlNormal
    implementation(libs.androidx.appcompat)
}
