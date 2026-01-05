import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.jetbrains.kotlin.compose)
    id("kotlin-parcelize")
    id("com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { fis ->
            load(fis)
        }
    }
}

val versionProperties = Properties().apply {
    val versionFile = rootProject.file("version.properties")
    if (versionFile.exists()) {
        versionFile.inputStream().use { load(it) }
    }
}

val vMajor = versionProperties.getProperty("versionMajor", "1").toInt()
val vMinor = versionProperties.getProperty("versionMinor", "2").toInt()
val defaultPatch = versionProperties.getProperty("versionPatch", "0").toInt()
val defaultBuild = versionProperties.getProperty("versionBuild", "0").toInt()

abstract class BuildVersionValueSource : ValueSource<Int, BuildVersionValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        @get:Input
        val workingDir: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): Int {
        return try {
            val output = ByteArrayOutputStream()
            execOperations.exec {
                workingDir = File(parameters.workingDir.get())
                commandLine("git", "rev-list", "--count", "HEAD")
                standardOutput = output
            }
            String(output.toByteArray()).trim().toInt()
        } catch (e: Exception) {
            -1
        }
    }
}

abstract class PatchVersionValueSource : ValueSource<Int, PatchVersionValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        @get:Input
        val workingDir: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): Int {
        return try {
            val blameOutput = ByteArrayOutputStream()
            execOperations.exec {
                workingDir = File(parameters.workingDir.get())
                commandLine("sh", "-c", "git blame -L '/versionMinor=/',+1 version.properties | awk '{print \$1}' | tr -d '^'")
                standardOutput = blameOutput
            }
            val commitHash = String(blameOutput.toByteArray()).trim()

            if (commitHash.isNotEmpty()) {
                val countOutput = ByteArrayOutputStream()
                execOperations.exec {
                    workingDir = File(parameters.workingDir.get())
                    commandLine("git", "rev-list", "--count", "$commitHash..HEAD")
                    standardOutput = countOutput
                }
                String(countOutput.toByteArray()).trim().toInt()
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }
}

val vBuild = providers.of(BuildVersionValueSource::class) {
    parameters.workingDir.set(rootProject.rootDir.absolutePath)
}.getOrElse(defaultBuild).let { if (it == -1) defaultBuild else it }

val vPatch = providers.of(PatchVersionValueSource::class) {
    parameters.workingDir.set(rootProject.rootDir.absolutePath)
}.getOrElse(defaultPatch).let { if (it == -1) defaultPatch else it }

android {
    buildFeatures {
        buildConfig = true
    }
    namespace = "com.hereliesaz.graffitixr"
    compileSdk = 36 // Required for Android XR

    defaultConfig {
        applicationId = "com.hereliesaz.graffitixr"
        minSdk = 26
        targetSdk = 36
        versionCode = vMajor * 10000000 + vMinor * 100000 + vPatch * 1000 + vBuild
        versionName = "$vMajor.$vMinor.$vPatch.$vBuild"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        multiDexEnabled = true
        ndk {
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
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
    implementation(libs.androidx.material.icons.extended)

    // ViewModel, Navigation, Coroutines, Coil
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.coil.compose)

    // Constraint Layout (Required for Sceneform-EQR)
    implementation(libs.androidx.constraintlayout)

    // ARCore
    implementation(libs.arcore.client)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // AndroidXR
    implementation(libs.androidx.xr.runtime)
    implementation(libs.androidx.xr.scenecore)
    implementation(libs.androidx.xr.compose)
    implementation(libs.androidx.xr.compose.material3)
    implementation(libs.androidx.xr.arcore)
    implementation(libs.google.accompanist.permissions)


    // ML Kit
    implementation(libs.mlkit.subject.segmentation)
    implementation(libs.segmentation.selfie)
    implementation(libs.play.services.base)
    implementation(libs.play.services.location)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

    // OpenCV
    implementation(libs.opencv)

    // Sceneform-EQR
    implementation(libs.eq.renderer)
    implementation(libs.eq.slam)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}