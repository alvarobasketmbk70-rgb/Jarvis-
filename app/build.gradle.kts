plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.asistente"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.asistente"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Detector local de «Oye Janus» (sin internet, sin «bip», sin cortar el audio)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    // Voz de JANUS incluida (sherpa-onnx + Piper): gratis y sin internet
    implementation(files("libs/sherpa-onnx.aar"))
}
