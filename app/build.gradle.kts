plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}


android {
    namespace = "de.spritbuch.app"
    compileSdk = 35
    // Gleiche NDK-Version wie Tesseract4Android: nötig, damit die nativen Bibliotheken ohne Debug-Infos verpackt werden
    ndkVersion = "27.2.12479018"

    defaultConfig {
        // Tesseract4Android gibt es als „standard“ und „openmp“; wir nehmen die einfache Variante
        missingDimensionStrategy("parallelization", "standard")
        applicationId = "io.github.ridgebackattack.fuelandwrench"
        minSdk = 26
        targetSdk = 35
        // Feste Version (F-Droid liest sie von hier). Für jedes Release um 10 erhöhen
        // und eine Änderungsnotiz unter fastlane/metadata/android/*/changelogs/<versionCode>.txt anlegen.
        // Die APKs je Prozessortyp bekommen +1 (armeabi-v7a) bzw. +2 (arm64-v8a), die Universal-APK +0.
        versionCode = 370
        versionName = "1.6.1"
    }

    // Eine kleine APK je Prozessortyp plus eine Universal-APK (für F-Droid/Reproducible Builds)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    // Release-Signatur: Schlüssel wird im CI aus app/release.jks.enc entschlüsselt (Passwort = Secret SIGNING_PASSWORD).
    // Lokal ohne Passwort wird mit dem Debug-Schlüssel signiert.
    val signingPassword = System.getenv("SIGNING_PASSWORD")
    val releaseKeystore = file(System.getenv("RELEASE_KEYSTORE") ?: "release.jks")
    signingConfigs {
        if (signingPassword != null && releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingPassword
                keyAlias = "fuelandwrench"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 entfernt ungenutzten Code (v. a. aus den Icon- und Compose-Bibliotheken): kleiner und schneller
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Android-Klassen in Unit-Tests liefern Standardwerte statt Fehler
    testOptions { unitTests.isReturnDefaultValues = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation(project(":tesseract4android"))
    // Installiert das Startprofil auch bei Installation außerhalb von Google Play (schnellerer Start)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    testImplementation("junit:junit:4.13.2")
}

// versionCode je APK: Basis + 0 (universal), +1 (armeabi-v7a), +2 (arm64-v8a)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val offset = when (abi) { "armeabi-v7a" -> 1; "arm64-v8a" -> 2; else -> 0 }
            output.versionCode.set(output.versionCode.get() + offset)
        }
    }
}
