pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pocketmobalert-lite"
include(":app")
include(":core")

include(":reliability")
project(":reliability").projectDir = file("trozovka-android-toolkit/reliability")
