plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.app"
        minSdk = 26          // Android 8.0 - deckt Doreens Galaxy locker ab
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
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

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // OkHttp fuer den multipart-Upload (Audio-Datei + Formfelder an
    // /assistant). Sehr etabliert, keine Versionskonflikte mit AGP 8.5.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
