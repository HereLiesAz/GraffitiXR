plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    
}

android {
    namespace = "com.hereliesaz.graffitixr.native"
    compileSdk = 34
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { cppFlags("-std=c++17"); arguments("-DANDROID_STL=c++_shared") } }
    }

    externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation(project(":core:common")); implementation(project(":core:domain"))
    
}
