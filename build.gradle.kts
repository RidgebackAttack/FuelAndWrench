plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

// Wird vom Build-Skript des Tesseract4Android-Submoduls erwartet
extra["tesseract4AndroidVersion"] = "4.9.0"

// Native Texterkennung: nur Handy-Architekturen bauen und Build-Pfade aus den Binärdateien
// heraushalten, damit F-Droid die APK Byte für Byte nachbauen kann (Reproducible Builds).
project(":tesseract4android") {
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
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
