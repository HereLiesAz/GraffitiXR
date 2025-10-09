plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    compileSdk = 36
    namespace = "com.vuforia"

    defaultConfig {
        minSdk = 26
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("lib")
        }
    }
}

dependencies {
    api(files("java/VuforiaEngine.jar"))
}
