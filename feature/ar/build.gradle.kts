plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    
}

android {
    namespace = "com.hereliesaz.graffitixr.feature.ar"
    compileSdk = 34
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
    }

    

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    buildFeatures { compose = true }; composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation(project(":core:common")); implementation(project(":core:domain")); implementation(project(":core:design")); implementation(project(":core:native"))
    implementation(platform("androidx.compose:compose-bom:2024.01.00")); implementation("androidx.compose.ui:ui"); implementation("androidx.compose.material3:material3"); implementation("androidx.compose.ui:ui-tooling-preview")
}
