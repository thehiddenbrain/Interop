plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.thehiddenbrain.interop"
version = "0.1.0-SNAPSHOT"
description = "Returns member identity, segmentation flags and family permissions for the League member portal at login"

java {
    // Java 17 language level and API; builds on any JDK 17 or newer.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.release = 17
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
}

val springdocVersion = "2.8.9"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Set MEMBER_PROFILE_TEST_DB=true to also run the end-to-end test against a local PostgreSQL.
    testLogging {
        events("passed", "skipped", "failed")
    }
}
