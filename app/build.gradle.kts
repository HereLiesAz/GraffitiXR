import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
    id("kotlin-parcelize")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { fis ->
            load(fis)
        }
    }
}

android {
    signingConfigs {
        create("release") {
            storeFile = file("G:\\My Drive\\az_apk_keystore.jks")
            storePassword = "18187077190901818"
            keyAlias = "key0"
            keyPassword = "18187077190901818"
        }
    }
    namespace = "com.hereliesaz.graffitixr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hereliesaz.graffitixr"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        multiDexEnabled = true
        buildConfigField("String", "VUFORIA_CLIENT_ID", "\"${localProperties.getProperty("vuforia.clientId")}\"")
        buildConfigField("String", "VUFORIA_CLIENT_SECRET", "\"${localProperties.getProperty("vuforia.clientSecret")}\"")
        buildConfigField("String", "VUFORIA_LICENSE_KEY", "\"${localProperties.getProperty("vuforia.licenseKey")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("../vuforia/lib")
        }
    }
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14033849 rc4"

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // ViewModel, Navigation, Coroutines, Coil, Vuforia
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.coil.compose)
    implementation(files("../vuforia/java/VuforiaEngine.jar"))

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // AndroidXR
    implementation(libs.google.accompanist.permissions)

    // AzNavRail
    implementation(libs.az.nav.rail)

    // Background Remover
    implementation(libs.auto.background.remover)

    // ML Kit
    implementation(libs.segmentation.selfie)



    // OpenCV
    implementation(libs.opencv)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
