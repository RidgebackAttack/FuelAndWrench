plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.android.library") version "9.5.1" apply false
    id("com.android.test") version "9.5.1" apply false
    // Startprofil: nur im eigenen Workflow (baseline.yml) aktiv, siehe app/build.gradle.kts
    id("androidx.baselineprofile") version "1.5.0" apply false
    // Kotlin ist ab AGP 9 eingebaut; diese Plug-ins legen die Kotlin-Version fest
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.14" apply false
}

// Wird vom Build-Skript des Tesseract4Android-Submoduls erwartet
extra["tesseract4AndroidVersion"] = "4.9.0"

// Native Texterkennung: nur Handy-Architekturen bauen und Build-Pfade aus den Binärdateien
// heraushalten, damit F-Droid die APK Byte für Byte nachbauen kann (Reproducible Builds).
project(":tesseract4android") {
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
            defaultConfig {
                ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
                externalNativeBuild {
                    cmake {
                        val map = "-ffile-prefix-map=${rootDir.absolutePath}=."
                        cFlags += map
                        cppFlags += map
                    }
                }
            }
        }
    }
}
