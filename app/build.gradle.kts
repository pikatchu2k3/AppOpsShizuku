plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.minipc.appopsshizuku"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.minipc.appopsshizuku"
        minSdk = 26
        targetSdk = 35
        versionCode = 42
        versionName = "1.20.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/**"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }
}

dependencies {
    // Shizuku-API: Shell-Privilegien (adb) ohne Root
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Material Design 3 (Google-Standard) + AppCompat-Basis
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // App-Detail mit Reitern (TabLayout aus material, Pager aus viewpager2)
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // On-Device-APK-Signierung (v1+v2+v3) + selbstsigniertes Zertifikat (Stealth-Modus)
    implementation("com.android.tools.build:apksig:8.2.2")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // On-Device-Dex-Patch (Schwaerz-Modus): baksmali + smali (einheitlich org.smali 2.5.2)
    implementation("org.smali:smali:2.5.2")
    implementation("org.smali:baksmali:2.5.2")
    implementation("org.smali:dexlib2:2.5.2")

    testImplementation("junit:junit:4.13.2")
    // Android-Unit-Tests haben kein echtes org.json (nur Stubs) -> echte Impl fuer JVM-Tests
    testImplementation("org.json:json:20240303")
}
