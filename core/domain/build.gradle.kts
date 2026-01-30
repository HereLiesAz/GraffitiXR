plugins {
    id("com.android.library")
    id("kotlin-parcelize")
}

android {
    namespace = "com.hereliesaz.graffitixr.domain"
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

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-XXLanguage:+PropertyParamAnnotationDefaultTargetMode")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(project(":core:common"))
    implementation(libs.androidx.compose.ui.geometry)
}
