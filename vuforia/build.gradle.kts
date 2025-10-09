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
            jniLibs.srcDirs("build/lib")
        }
    }
}

dependencies {
    api(files("build/java/VuforiaEngine.jar"))
}
