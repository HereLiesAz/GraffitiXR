plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vuforia.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
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

    buildFeatures {
        prefabPublishing = true
    }

    // This block tells consumers (your app) where to find the headers.
    prefab {
        vuforia {
            headers = "sdk/include"
        }
    }

    // This block tells Gradle where the pre-built .so files are.
    sourceSets {
        main {
            jniLibs.srcDirs("sdk/lib")
        }
    }
}

dependencies {}
