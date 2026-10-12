import dev.detekt.gradle.extensions.FailOnSeverity
import gg.jte.ContentType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.jte)
}

group = "io.github.big-sw-little-sw"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Folio validates TOML files with this module. The Jackson BOM of Spring Boot sets its version.
    implementation("tools.jackson.dataformat:jackson-dataformat-toml")
    implementation(libs.springdoc.openapi.webmvc.api)
    implementation(libs.jte.kotlin)
    implementation(libs.jte.spring.boot.starter)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-session-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("${libs.spring.modulith.bom.get()}")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// The build generates Kotlin sources from the .kte templates, so a template error stops the build.
// The package puts each template class in the module of its directory, for example `web/layout.kte` in `web`.
jte {
    sourceDirectory = file("src/main/jte").toPath()
    contentType = ContentType.Html
    packageName = "io.github.big_sw_little_sw.folio"
    generate()
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("generateJte"))
}

// ktlint skips the generated templates, but the ktlint tasks still read the generated-sources directory. Without a
// dependency on generateJte, Gradle stops the build, because the ktlint tasks use an undeclared task output.
ktlint {
    version = libs.versions.ktlint
    filter {
        exclude { it.file.path.contains("/generated-sources/") }
    }
}

tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
    dependsOn(tasks.named("generateJte"))
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("config/detekt/detekt.yml")
    // Any finding fails the build, whatever its severity.
    failOnSeverity = FailOnSeverity.Info
}

// The Spring dependency-management plugin pins Kotlin artifacts to the project's Kotlin version in every
// configuration. Without this override, detekt runs with a Kotlin version that it was not compiled with and fails.
configurations.named("detekt") {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(libs.versions.detektKotlin.get())
        }
    }
}

// Each JVM that runs Folio uses compact object headers. Smaller objects use less memory and cache.
val folioJvmArgs = listOf("-XX:+UseCompactObjectHeaders")

tasks.bootRun {
    jvmArgs(folioJvmArgs)
}

tasks.bootTestRun {
    jvmArgs(folioJvmArgs)
}

tasks.bootBuildImage {
    environment.putAll(
        mapOf(
            "BP_JVM_VERSION" to "25",
            "BPE_DELIM_JAVA_TOOL_OPTIONS" to " ",
            "BPE_APPEND_JAVA_TOOL_OPTIONS" to folioJvmArgs.joinToString(" "),
        ),
    )
}

tasks.withType<Test>().configureEach {
    jvmArgs(folioJvmArgs)
}

// Tests with the tag "integration" use Testcontainers and need Docker. The `test` task excludes them, so `test` runs
// on a machine without Docker. The `check` task runs both tasks.
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs tests tagged 'integration' (requires Docker)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val test = sourceSets.test.get()
    testClassesDirs = test.output.classesDirs
    classpath = test.runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn("integrationTest")
}
