plugins {
    id("com.android.library")
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.hereliesaz.graffitixr.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // Compose BOM only if needed for UI code in common
    implementation(libs.androidx.compose.material3)
    
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.compose.ui.geometry)
    testImplementation(libs.junit)
    
    implementation(project(":opencv"))
    implementation(libs.play.services.location)
    
    // Explicitly add parcelize runtime to resolve unresolved reference
    implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.1.0")
}
