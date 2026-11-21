plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shortstop.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shortstop.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 37
        versionName = "9.1"
    }

    // One fixed key for every build, so new APKs install over old ones.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

// Zero third-party dependencies on purpose: plain Android views only.
