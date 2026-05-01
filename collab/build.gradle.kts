plugins {
    id("com.android.library")
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.hereliesaz.graffitixr.core.collaboration"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(libs.kotlinx.serialization.cbor)
    testImplementation(libs.junit)
}
