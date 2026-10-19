plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.erick.gamebypass"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.erick.gamebypass"
        minSdk = 30
        targetSdk = 35
        // GitHub passes its build number in, so build-7 on GitHub is version 7 here.
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "build $buildNumber"
        // Only the 64-bit phone code (your Pixel), which makes the APK much smaller.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Same key every build, so new versions install over old ones.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
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
}

dependencies {
    // Google's offline text reader (OCR). The model is bundled inside the app.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Smart labels: the phone's built-in offline AI (Gemini Nano) describing pictures.
    implementation("com.google.mlkit:genai-image-description:1.0.0-beta1")
}
