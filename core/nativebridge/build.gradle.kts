plugins {
    id("com.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.hereliesaz.graffitixr.nativebridge"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Build only for ARM architectures (skip x86/x86_64 emulator builds)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Consume the OpenCV Maven artifact's Prefab part from CMake (find_package(OpenCV)).
    buildFeatures {
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(libs.arcore.client)
    // OpenCV from Maven Central. Its Prefab part exposes the native C++ world to CMake
    // (find_package(OpenCV) → OpenCV::opencv_java5) and auto-packages libopencv_java5.so.
    implementation(libs.opencv)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}

// Pin Kotlin's JVM target to match Java (17). Without this, Kotlin defaults to a lower target
// than the Java sources, which AGP flags as an inconsistent JVM-target compatibility error.
// Uses the same task-based approach as :app (this module applies only AGP + KSP, so the
// `kotlin {}` extension is not registered).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
