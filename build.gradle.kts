// Declares the plugins both modules share, so Gradle loads each of them once for the whole build
// instead of once per module (the Kotlin Gradle plugin does not support the latter).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.detekt) apply false
}
