plugins {
    id("com.android.library")
}

android {
    namespace = "com.hereliesaz.graffitixr.design"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        compose = false
    }

}

dependencies {
    implementation(project(":core:common"))

    // UI Frameworks
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // MISSING DEPENDENCY RESTORED:
    // Required for legacy vector drawables using ?attr/colorControlNormal
    implementation(libs.androidx.appcompat)
}
