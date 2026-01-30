plugins {
    id("com.android.library")
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
    implementation(libs.timber)
    implementation(libs.opencv)
}
