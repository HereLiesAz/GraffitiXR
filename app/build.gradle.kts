import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.hereliesaz.graffitixr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hereliesaz.graffitixr"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
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

    // FIX: Removed the failing top-level 'kotlin {}' block.
    // We configure JVM target via tasks below.

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// FIX: Configure Kotlin JVM target safely using tasks
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:nativebridge"))
    implementation(project(":feature:ar"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:dashboard"))
    implementation(project(":core:common"))
    implementation(project(":core:design"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)

    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.timber)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(project(":opencv"))

    implementation(libs.az.nav.rail)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // FIX: Corrected reference to match TOML 'compose-ui-test-junit4'
    androidTestImplementation(libs.compose.ui.test.junit4)
    // FIX: Corrected reference to match TOML 'compose-ui-test-manifest'
    debugImplementation(libs.compose.ui.test.manifest)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.register("updateAzNavDocs") {
    group = "documentation"
    description = "Extracts AzNavRail documentation from the dependency."

    doLast {
        // Find the AzNavRail AAR in the runtime classpath
        val artifact = configurations.getByName("debugRuntimeClasspath").files
            .find { it.name.contains("AzNavRail") && it.extension == "aar" }

        if (artifact != null) {
            copy {
                from(zipTree(artifact))
                include("assets/AZNAVRAIL_COMPLETE_GUIDE.md")
                into(layout.projectDirectory.dir("docs"))
                // Remove the 'assets/' prefix from the output file
                eachFile {
                    path = name
                }
                includeEmptyDirs = false
            }
            println("AzNavRail documentation updated: docs/AZNAVRAIL_COMPLETE_GUIDE.md")
        } else {
            println("AzNavRail AAR not found. Make sure the dependency is added.")
        }
    }
}
