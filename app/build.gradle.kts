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
        versionCode = 1
        versionName = "0.1"
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
    // Bewusst minimal fuer den ersten Build: nur appcompat fuer die Activity.
    // Netzwerk laeuft ueber Android-Bordmittel (HttpURLConnection + org.json),
    // Audio/Kamera-Bibliotheken kommen erst in spaeteren Etappen dazu.
    implementation("androidx.appcompat:appcompat:1.7.0")
}
