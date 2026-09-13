plugins {
    `java-library`
    id("com.diffplug.spotless") version "8.10.2"
}

group = "cc.odexa.qa"
version = "0.1.0-SNAPSHOT"

repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    implementation("io.rest-assured:rest-assured:6.0.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.2")
    implementation("org.assertj:assertj-core:3.27.7")
    implementation("org.awaitility:awaitility:4.3.0")
    implementation("io.qameta.allure:allure-java-commons:2.35.5")
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
    listOf("env", "baseUrl", "tokenUrl", "clientId", "productId", "otherProductId", "tenantA", "tenantB",
        "version", "allowMutation", "exclusiveFixtures", "timeoutSeconds", "pollTimeoutSeconds", "pollIntervalMillis", "verbose").forEach { key ->
        providers.systemProperty("odexa.$key").orNull?.let { systemProperty("odexa.$key", it) }
    }
}
tasks.test { useJUnitPlatform { excludeTags("api") } }

val apiTest by tasks.registering(Test::class) {
    description = "Runs the independent HTTP suite against the configured Odexa target."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("api")
        if (providers.systemProperty("odexa.allowMutation")
            .orElse(providers.environmentVariable("ODEXA_ALLOW_MUTATION")).orElse("false").get() != "true") {
            excludeTags("mutation")
        }
    }
    outputs.upToDateWhen { false }
    outputs.cacheIf("Live HTTP verification must always execute") { false }
}
