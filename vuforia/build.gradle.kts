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

    prefab {
        vuforia {
            headers("src/main/prefab/headers")
            libraryName = "VuforiaEngine"
        }
    }

    sourceSets {
        named("main") {
            jniLibs.srcDirs("sdk/lib")
        }
    }
}

dependencies {}
