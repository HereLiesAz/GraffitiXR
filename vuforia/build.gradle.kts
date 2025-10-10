plugins {
    id("com.android.library")
    id("kotlin-android")
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("lib")
            java.srcDirs("java")
        }
    }
}
