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
val vMinor = versionProperties.getProperty("versionMinor", "15").toInt()
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
        compose = true
    }
    namespace = "com.hereliesaz.graffitixr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hereliesaz.graffitixr"
        minSdk = 32
        targetSdk = 36
        versionCode = vMajor * 10000000 + vMinor * 100000 + vPatch * 1000 + vBuild
        versionName = "$vMajor.$vMinor.$vPatch.$vBuild"

        val arcoreApiKey = localProperties.getProperty("ARCORE_API_KEY") ?: System.getenv("ARCORE_API_KEY") ?: ""
        resValue("string", "arcore_api_key", arcoreApiKey)

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
                cppFlags("-std=c++17")
                // OpenCV_DIR is now calculated in CMakeLists.txt to avoid sync issues
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            val storeFileProp = project.findProperty("android.injected.signing.store.file")?.toString()
            val storePasswordProp = project.findProperty("android.injected.signing.store.password")?.toString()
            val keyAliasProp = project.findProperty("android.injected.signing.key.alias")?.toString()
            val keyPasswordProp = project.findProperty("android.injected.signing.key.password")?.toString()

            if (storeFileProp != null && File(storeFileProp).exists()) {
                storeFile = File(storeFileProp)
                storePassword = storePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
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
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += "lib/arm64-v8a/libc++_shared.so"
            pickFirsts += "lib/arm64-v8a/libopencv_java4.so"
            pickFirsts += "lib/armeabi-v7a/libopencv_java4.so"
            pickFirsts += "lib/x86/libopencv_java4.so"
            pickFirsts += "lib/x86_64/libopencv_java4.so"
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

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.coil.compose)

    implementation(libs.androidx.constraintlayout)

    implementation(libs.arcore.client)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.xr.runtime)
    implementation(libs.androidx.xr.scenecore)
    implementation(libs.androidx.xr.compose)
    implementation(libs.androidx.xr.compose.material3)
    implementation(libs.androidx.xr.arcore)
    implementation(libs.google.accompanist.permissions)

    implementation(libs.az.nav.rail)

    implementation(libs.mlkit.subject.segmentation)
    implementation(libs.segmentation.selfie)
    implementation(libs.play.services.base)
    implementation(libs.play.services.location)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

    // implementation(libs.opencv)

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