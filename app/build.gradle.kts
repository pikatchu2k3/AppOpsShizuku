plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "de.minipc.appopsshizuku"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.minipc.appopsshizuku"
        minSdk = 26
        targetSdk = 35
        versionCode = 51
        versionName = "1.21.1"
    }

    // F-Droid/IzzyOnDroid: keinen verschluesselten Abhaengigkeits-Block (Google-Blob) in die APK schreiben.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Zwei Ausgaben mit derselben Anwendungs-ID:
    //  - full: mit WLAN-Regeln (braucht Standort-Rechte, um den WLAN-Namen zu lesen) - wie bisher
    //  - lite: ohne WLAN-Regeln und OHNE jedes Standort-Recht im Manifest
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "WIFI_RULES", "true")
        }
        create("lite") {
            dimension = "edition"
            buildConfigField("boolean", "WIFI_RULES", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // R8 (Verkleinern, ohne Umbenennen) ist ein Opt-in: -Pminify=true. Standard ist aus, weil smali/dexlib2,
            // BouncyCastle und Shizuku nicht auf dem Geraet geprueft sind - siehe app/proguard-rules.pro.
            val minify = providers.gradleProperty("minify").orNull == "true"
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
            // Veroeffentlichte APKs behalten das bisherige Zertifikat (Updates ueber bestehende Installationen,
            // siehe README "Signatur"), sind aber echte Release-Builds (nicht debuggable).
            // Ohne die Property bleibt das APK unsigniert - so baut F-Droid und signiert selbst.
            if (providers.gradleProperty("debugSignedRelease").orNull == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
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
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // Material Design 3 (Google-Standard) + AppCompat-Basis
    implementation(libs.material)
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.recyclerview)
    // App-Detail mit Reitern (TabLayout aus material, Pager aus viewpager2)
    implementation(libs.viewpager2)

    // On-Device-APK-Signierung (v1+v2+v3) + selbstsigniertes Zertifikat (Stealth-Modus)
    implementation(libs.apksig)
    implementation(libs.bouncycastle.pkix)

    // On-Device-Dex-Patch (Schwaerz-Modus): baksmali + smali (einheitlich org.smali)
    implementation(libs.smali)
    implementation(libs.smali.baksmali)
    implementation(libs.smali.dexlib2)

    testImplementation(libs.junit)
    // Android-Unit-Tests haben kein echtes org.json (nur Stubs) -> echte Impl fuer JVM-Tests
    testImplementation(libs.org.json)
}
