import java.util.Properties

plugins {
    id("com.android.application")
    alias(libs.plugins.jetbrains.kotlin.compose)
}


val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val arcoreApiKey = localProperties.getProperty("ARCORE_API_KEY") ?: ""

android {
    namespace = "com.hereliesaz.graffitixr"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hereliesaz.graffitixr"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        resValue("string", "arcore_api_key", arcoreApiKey)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }
    ndkVersion = "28.2.13676358"
    buildToolsVersion = "36.1.0"
    compileSdkMinor = 1
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:design"))
    implementation(project(":core:native"))
    implementation(project(":feature:ar"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:dashboard"))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.opencv)
    implementation(libs.arcore.client)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.az.nav.rail)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
}
