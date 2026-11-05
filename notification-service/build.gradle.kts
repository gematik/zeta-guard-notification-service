import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    id("io.quarkus")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spotless)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.detekt)
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.cyclonedx)
    jacoco
}

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        name = "gitlab"
        url = uri("https://tas-devtools-gitlab.spree.de/api/v4/groups/8/-/packages/maven")
        credentials(HttpHeaderCredentials::class) {
            val jobToken = System.getenv("CI_JOB_TOKEN")
            name = if (jobToken != null) "Job-Token" else "Private-Token"
            value = jobToken ?: providers.gradleProperty("gitLabPrivateToken").orNull
        }
        authentication { create<HttpHeaderAuthentication>("header") }
        content { includeGroup("de.gematik.zeta.zeta-guard") }
    }
}

val hsmProxyProviderUberJar: Configuration by configurations.creating { isTransitive = false }

val repackageHsmProxyProvider =
    tasks.register<Jar>("repackageHsmProxyProvider") {
        archiveFileName.set("java-hsm-proxy-provider-repackaged.jar")
        destinationDirectory.set(layout.buildDirectory.dir("repackaged"))
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(hsmProxyProviderUberJar.elements.map { jars -> jars.map { zipTree(it) } }) {
            include("de/gematik/zetaguard/**", "META-INF/java-hsm-proxy-provider.kotlin_module")
        }
    }

// Hands the repackaged jar to the keychain-generator module, so both use the same client.
val hsmProxyProvider: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(hsmProxyProvider.name, repackageHsmProxyProvider)
}

// Pins the whole transitive closure to notification-service/gradle.lockfile, so a given commit
// always resolves the same jars: the release images are signed and Trivy-scanned, which only means
// something if the build is reproducible from the commit. STRICT additionally rejects a locked
// configuration that has no lock state, so a new source set or plugin can't slip through unpinned.
// Refresh with --write-locks after any dependency change (see CLAUDE.md).
dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}

// The Kotlin Gradle plugin resolves its ABI-validation tooling classpath with an open version
// range, so its lock state drifts with every Kotlin release. Nothing of it reaches the artifact.
configurations.matching { it.name == "kotlinAbiValidationCompatClasspath" }.configureEach {
    resolutionStrategy.deactivateDependencyLocking()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation("io.quarkus:quarkus-opentelemetry")
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.kotlin.serialization)
    implementation(libs.quarkus.rest.client)
    implementation(libs.quarkus.rest.client.kotlin.serialization)
    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.security)
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.quarkus.arc)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.mutiny.kotlin)
    // One image serves every supported engine: each JDBC extension bakes its driver in at build
    // time (quarkus.datasource.db-kind is build-time fixed), and the datasource left without a
    // jdbc.url at runtime deactivates itself. See README "Supported databases".
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.jdbc.mariadb)
    implementation(libs.quarkus.jdbc.mysql)
    implementation(libs.quarkus.jdbc.mssql)
    implementation(libs.quarkus.jdbc.oracle)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.flyway.postgresql)
    // quarkus-flyway-mariadb does not exist; the MySQL module covers MariaDB too.
    implementation(libs.quarkus.flyway.mysql)
    implementation(libs.quarkus.flyway.mssql)
    implementation(libs.quarkus.flyway.oracle)
    implementation(libs.bundles.bouncycastle)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.grpc)
    implementation(libs.proto.google.common.protos)
    hsmProxyProviderUberJar(libs.hsm.proxy.provider)
    implementation(files(repackageHsmProxyProvider))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.jacoco)
    testImplementation(libs.rest.assured)
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.wiremock)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.mssqlserver)
    testImplementation(libs.testcontainers.oracle)
    // jackson is deliberately only included in test dependencies, using it in production will break native build!
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.core)

    quarkusDev("io.quarkus:quarkus-observability-devservices-lgtm")
}

val openApiTemplateDir = "$projectDir/config/openapi-templates/kotlin-server"

fun GenerateTask.configureCodegen(
    specFileName: String,
    subPackage: String,
) {
    generatorName.set("kotlin-server")
    library.set("jaxrs-spec")
    inputSpec.set(rootProject.file("docs/$specFileName").toString())
    outputDir.set(
        layout.buildDirectory
            .dir("generated/openapi/$subPackage")
            .get()
            .toString(),
    )
    apiPackage.set("de.gematik.zeta.notificationservice.adapter.inbound.$subPackage.generated.api")
    modelPackage.set("de.gematik.zeta.notificationservice.adapter.inbound.$subPackage.generated.model")
    templateDir.set(openApiTemplateDir)
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useJakartaEe" to "true",
            "returnResponse" to "true",
        ),
    )
    // kotlin-server has no built-in kotlinx.serialization support for these JDK types (compile
    // fails with "Serializer has not been found"); map them to types kotlinx handles natively.
    typeMappings.set(
        mapOf(
            "URI" to "kotlin.String",
            "UUID" to "kotlin.uuid.Uuid",
            "DateTime" to "kotlin.time.Instant",
            "object" to "JsonElement",
            "AnyType" to "JsonElement",
        ),
    )
    importMappings.set(
        mapOf(
            "JsonElement" to "kotlinx.serialization.json.JsonElement",
        ),
    )
}

val generateFdvApi =
    tasks.register<GenerateTask>("generateFdvApi") {
        configureCodegen("openApi_notification_fachdienst.yaml", "fdv")
    }

val generateRsApi =
    tasks.register<GenerateTask>("generateRsApi") {
        configureCodegen("notification_service_rs_endpoint.yaml", "rs")
    }

val generatePushGatewayApi =
    tasks.register<GenerateTask>("generatePushGatewayApi") {
        configureCodegen("push_gateway.yaml", "pushgateway")
    }

val generatePushGatewayStubs =
    tasks.register<GenerateTask>("generatePushGatewayStubs") {
        generatorName.set("kotlin-wiremock")
        inputSpec.set(rootProject.file("docs/push_gateway.yaml").toString())
        outputDir.set(
            layout.buildDirectory
                .dir("generated/wiremock/pushgateway")
                .get()
                .toString(),
        )
        // Adjust configuration options as needed
        configOptions.set(
            mapOf(
                "library" to "junit5", // Example configuration, adjust as needed
            ),
        )
    }

sourceSets {
    main {
        kotlin {
            srcDir(layout.buildDirectory.dir("generated/openapi/fdv/src/main/kotlin"))
            srcDir(layout.buildDirectory.dir("generated/openapi/rs/src/main/kotlin"))
            srcDir(layout.buildDirectory.dir("generated/openapi/pushgateway/src/main/kotlin"))
        }
    }
    test {
        kotlin {
            srcDir(layout.buildDirectory.dir("generated/wiremock/pushgateway/src/main/kotlin"))
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    dependsOn(generateFdvApi, generateRsApi, generatePushGatewayApi, generatePushGatewayStubs)
}

// Which engine a test runs against is named in its own @WithDatabase annotation, and every engine
// runs on every invocation - the suite is the whole truth, with nothing to opt into.
tasks.test {
    useJUnitPlatform()
}

// Every Test task (test, quarkusIntTest, testNative) runs many Quarkus augmentations/restarts in one
// worker JVM - one per engine and profile - so Gradle's 512m default worker heap is too tight.
tasks.withType<Test>().configureEach {
    // Many Quarkus restarts (5 engines × profiles) in one worker JVM; 512m default is too tight.
    maxHeapSize = "2g"
}

// The plain jar isn't runnable (no Main-Class/dependencies) and isn't used - quarkusBuild
// produces the actual deployable artifact under build/quarkus-app/ independently.
tasks.named("jar") {
    enabled = false
}

group = "de.gematik.zeta"
// the version has to be hardcoded into the gitlab ci for native tests,
// because Gradle couldn't find the version otherwise. if this is ever changed, the ci needs changing too
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
        javaParameters = true
    }
}

spotless {
    val ktlintVersion = libs.versions.ktlint.get()

    kotlin {
        targetExclude("**/generated/**")
        ktlint(ktlintVersion)
        licenseHeaderFile(rootProject.file("config/license-header.txt"))
    }

    kotlinGradle {
        ktlint(ktlintVersion)
    }
}

detekt {
    buildUponDefaultConfig = true
    baseline = rootProject.file("config/detekt/baseline.xml")
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    exclude("**/generated/**")
    reports {
        html.required = true
        checkstyle.required = true // checkstyle-format XML consumed by SonarQube
        markdown.required = false
    }
}

sonar {
    properties {
        property("sonar.projectKey", "zeta_zeta-guard_notification-service_6ffdc680-b9c7-4e2e-97ab-1558cb4634f7")
        property("sonar.projectName", "notification-service")
        property("sonar.sources", "src/main")
        property("sonar.tests", "src/test")
        // Generated OpenAPI code is excluded from analysis (matches detekt/spotless).
        property("sonar.exclusions", "**/generated/**")
        property(
            "sonar.kotlin.detekt.reportPaths",
            layout.buildDirectory
                .file("reports/detekt/detekt.xml")
                .get()
                .toString(),
        )
        property(
            "sonar.junit.reportPaths",
            layout.buildDirectory
                .dir("test-results/test")
                .get()
                .toString(),
        )
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory
                .file("reports/jacoco/test/jacocoTestReport.xml")
                .get()
                .toString(),
        )
    }
}

tasks.withType<dev.detekt.gradle.DetektCreateBaselineTask>().configureEach {
    exclude("**/generated/**")
}

// Generated OpenAPI code is excluded from coverage
val coverageClassDirectories =
    files(
        layout.buildDirectory
            .dir("classes/kotlin/main")
            .get()
            .asFileTree
            .matching { exclude("**/generated/**") },
    )

// @QuarkusTest coverage is recorded by the quarkus-jacoco extension into a separate exec file;
// merge it with the plain unit-test exec so the report reflects both.
val coverageExecutionData =
    files(
        layout.buildDirectory.file("jacoco/test.exec"),
        layout.buildDirectory.file("jacoco-quarkus.exec"),
    )

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(coverageClassDirectories)
    executionData.setFrom(coverageExecutionData)

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.named("sonar") {
    dependsOn(tasks.jacocoTestReport, tasks.named("detekt"))
}
