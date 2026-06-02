plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.conversationalai.agent"
    compileSdk = 35
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.conversationalai.agent"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        ndk {
            // SM8750 is arm64; we only ship arm64-v8a.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // QAIRT/Genie .so files are copied into this dir by the prepare step (gitignored).
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
    packaging {
        jniLibs {
            useLegacyPackaging = true   // QAIRT skel/stub libs must be real files at runtime
        }
    }
}

dependencies {
    // Owned offline ASR engine (sherpa-onnx): prebuilt AAR (arm64 JNI + Kotlin API) in app/libs.
    // SenseVoice int8 model is pushed to app storage separately (not bundled in the APK).
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
