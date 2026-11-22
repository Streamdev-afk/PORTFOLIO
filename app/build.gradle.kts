plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pulse.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pulse.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-feasibility"
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

// Phase 1 is deliberately dependency-light: only AndroidX core (for ContextCompat).
// Compose, DataStore and coroutines arrive in Phase 3 when the Studio UI is built.
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
