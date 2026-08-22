plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "1.9.23"
}

android {
    namespace = "de.minipc.spooftest"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.minipc.spooftest"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-ads-identifier:18.0.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
}