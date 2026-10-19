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
        versionCode = 2
        versionName = "0.2"
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
}
