pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "FuelAndWrench"
include(":app")

// Texterkennung (Tesseract4Android 4.9.0, Apache-2.0) wird aus dem Quellcode im Git-Submodul gebaut
include(":tesseract4android")
project(":tesseract4android").projectDir = file("external/Tesseract4Android/tesseract4android")

// Erzeugt das Startprofil auf einem Emulator (nur mit -PbaselineProfile, Workflow baseline.yml)
if (providers.gradleProperty("baselineProfile").isPresent) include(":baselineprofile")
