plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
    application
}

repositories {
    mavenCentral()
}

// Same reasoning as in notification-service/build.gradle.kts: the image is signed, so the build
// has to be reproducible from the commit. Refresh with --write-locks after any dependency change.
dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}

configurations.matching { it.name == "kotlinAbiValidationCompatClasspath" }.configureEach {
    resolutionStrategy.deactivateDependencyLocking()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    // Only for its version management: gRPC, protobuf and kotlinx-serialization stay aligned with
    // the service. No Quarkus code ends up in the generator.
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    // The provider's client, repackaged by notification-service (see CLAUDE.md for why).
    implementation(project(":notification-service", "hsmProxyProvider"))
    implementation(libs.grpc.netty)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.proto.google.common.protos)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
}

application {
    mainClass = "de.gematik.zeta.notificationservice.keychaingenerator.KeychainGeneratorKt"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
    }
}

spotless {
    val ktlintVersion = libs.versions.ktlint.get()

    kotlin {
        ktlint(ktlintVersion)
        licenseHeaderFile(rootProject.file("config/license-header.txt"))
    }

    kotlinGradle {
        ktlint(ktlintVersion)
    }
}

detekt {
    buildUponDefaultConfig = true
}
