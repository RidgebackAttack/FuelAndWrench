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

    defaultConfig {
        applicationId = "io.github.ridgebackattack.fuelandwrench"
        minSdk = 26
        targetSdk = 35
        // Feste Version (F-Droid liest sie von hier). Für jedes Release hochzählen
        // und eine Änderungsnotiz unter fastlane/metadata/android/*/changelogs/<versionCode>.txt anlegen.
        versionCode = 29
        versionName = "1.2.1"
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

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
}
