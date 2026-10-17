// A deployable Spring Boot service in services/: web API, health checks, its own PostgreSQL
// database with Flyway, Kafka through the outbox, and every shared library.
plugins {
    id("solace.java-conventions")
    id("solace.spring-platform")
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":libs:log-scrub"))
    implementation(project(":libs:locale"))
    implementation(project(":libs:error-model"))
    implementation(project(":libs:service-security"))
    implementation(project(":libs:audit"))
    implementation(project(":libs:event-envelope"))
    implementation(project(":contracts"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "${project.name}.jar"
}

// Libraries only; the service is shipped as the boot jar.
tasks.named<Jar>("jar") {
    enabled = false
}
