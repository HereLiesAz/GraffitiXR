import java.util.Properties

plugins {
    id("com.android.library")
    id("kotlin-android")
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
            keyAlias = localProperties.getProperty("keyAlias")
            keyPassword = localProperties.getProperty("keyPassword")
            storeFile = localProperties.getProperty("storeFile")?.let { file(it) }
            storePassword = localProperties.getProperty("storePassword")
        }
    }
    compileSdk = 36
    namespace = "com.vuforia"

    defaultConfig {
        minSdk = 26
        targetSdk = 36
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
