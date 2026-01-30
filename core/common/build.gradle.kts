plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    
}

android {
    namespace = "com.hereliesaz.graffitixr.common"
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

    
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    
}
