import java.time.Duration

plugins {
    `java-library`
    id("com.diffplug.spotless") version "8.10.2"
}

group = "cc.odexa.qa"
version = "0.1.0-SNAPSHOT"

repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

val provisioning by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
val provisioningTest by sourceSets.creating {
    compileClasspath += provisioning.output + sourceSets.main.get().output
    runtimeClasspath += provisioning.output + sourceSets.main.get().output
}
configurations[provisioning.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[provisioningTest.implementationConfigurationName].extendsFrom(
    configurations[provisioning.implementationConfigurationName], configurations.testImplementation.get())
configurations[provisioningTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get(), configurations[provisioning.runtimeOnlyConfigurationName])

dependencies {
    implementation("io.rest-assured:rest-assured:6.0.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.2")
    implementation("org.assertj:assertj-core:3.27.7")
    implementation("org.awaitility:awaitility:4.3.0")
    implementation("io.qameta.allure:allure-java-commons:2.35.5")
    "provisioningRuntimeOnly"("org.slf4j:slf4j-nop:2.0.17")
    "provisioningImplementation"(platform("org.testcontainers:testcontainers-bom:2.0.4"))
    "provisioningImplementation"("org.testcontainers:testcontainers")
    "provisioningImplementation"("org.testcontainers:testcontainers-postgresql")
    "provisioningImplementation"("org.testcontainers:testcontainers-kafka")
    "provisioningImplementation"(platform("org.junit:junit-bom:5.14.4"))
    "provisioningImplementation"("org.junit.platform:junit-platform-launcher")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("io.qameta.allure:allure-junit5:2.35.5")
}

dependencyLocking { lockAllConfigurations() }

spotless {
    java { googleJavaFormat("1.36.1") }
    kotlinGradle { trimTrailingWhitespace(); endWithNewline() }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation", "-Werror"))
}
tasks.withType<Test>().configureEach {
    maxHeapSize = "512m"
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
    val allureResults = layout.buildDirectory.dir("allure-results/${name}")
    systemProperty("allure.results.directory", allureResults.get().asFile.absolutePath)
    outputs.dir(allureResults)
    doFirst { delete(allureResults) }
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", "4")
    // Credentials are environment-only at the process boundary; never construct secret JVM arguments.
    listOf("env", "targetMode", "baseUrl", "tokenUrl", "clientId", "productId", "otherProductId", "tenantA", "tenantB",
        "version", "allowMutation", "exclusiveFixtures", "timeoutSeconds", "pollTimeoutSeconds", "pollIntervalMillis", "verbose").forEach { key ->
        providers.systemProperty("odexa.$key").orNull?.let { systemProperty("odexa.$key", it) }
    }
}
tasks.test { useJUnitPlatform { excludeTags("api") } }

// A single configuration function selects the same compiled scenario classes for both lifecycles.
fun Test.blackBoxSuite(mutations: Provider<String>) {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    useJUnitPlatform {
        includeTags("api")
        if (!mutations.get().equals("true", ignoreCase = true)) excludeTags("mutation")
    }
    outputs.upToDateWhen { false }
    outputs.cacheIf("Live HTTP verification must always execute") { false }
    maxParallelForks = 1
}

val apiTest by tasks.registering(Test::class) {
    description = "Runs the HTTP suite against an existing REMOTE or COMPOSE target."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    blackBoxSuite(providers.systemProperty("odexa.allowMutation")
        .orElse(providers.environmentVariable("ODEXA_ALLOW_MUTATION")).orElse("false"))
}
val testcontainersTest by tasks.registering(Test::class) {
    description = "Provisions pinned Odexa and runs the same HTTP suite with exclusive fixtures."
    group = "verification"
    timeout.set(Duration.ofMinutes(45))
    classpath = sourceSets.test.get().runtimeClasspath + provisioning.runtimeClasspath
    blackBoxSuite(providers.provider { "true" })
    systemProperty("odexa.targetMode", "testcontainers")
    systemProperty("odexa.diagnostics.directory", layout.buildDirectory.dir("diagnostics").get().asFile.absolutePath)
}
val provisioningUnitTest by tasks.registering(Test::class) {
    description = "Checks provisioning policy and lifecycle without Docker."
    group = "verification"
    testClassesDirs = provisioningTest.output.classesDirs
    classpath = provisioningTest.runtimeClasspath
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
}
tasks.check { dependsOn(provisioningUnitTest) }

// Guard the resolved classpath too: transitive Docker dependencies must never enter REMOTE.
val verifyExecutionBoundaries by tasks.registering {
    group = "verification"
    doLast {
        val forbidden = setOf("org.testcontainers", "com.github.docker-java", "org.postgresql", "org.apache.kafka")
        for (name in listOf("runtimeClasspath", "testRuntimeClasspath")) {
            check(configurations[name].resolvedConfiguration.resolvedArtifacts.none { it.moduleVersion.id.group in forbidden }) {
                "Container/database/broker dependency reached the HTTP runtime: $name"
            }
        }
        check(apiTest.get().testClassesDirs.files == testcontainersTest.get().testClassesDirs.files) {
            "Execution modes must use identical compiled API scenarios"
        }
    }
}
tasks.check { dependsOn(verifyExecutionBoundaries) }
