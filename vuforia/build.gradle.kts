plugins {
    id("com.android.library")
}

android {
    namespace = "com.hereliesaz.graffitixr.vuforia"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    api(files("../app/libs/vuforia.aar"))
}
