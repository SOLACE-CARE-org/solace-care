// Event envelope, transactional outbox (Spring Modulith event publication) to Kafka,
// idempotent consumers and dead-letter topics.
plugins {
    id("solace.java-library")
}

dependencies {
    api("org.springframework.kafka:spring-kafka")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("org.springframework.modulith:spring-modulith-events-api")
    implementation("org.springframework.modulith:spring-modulith-events-core")
    implementation("org.springframework.modulith:spring-modulith-events-jdbc")
    implementation("org.springframework.modulith:spring-modulith-events-kafka")
    implementation("org.springframework.modulith:spring-modulith-events-jackson")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-tx")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.slf4j:slf4j-api")
    compileOnly("org.flywaydb:flyway-core")
    compileOnly("io.opentelemetry:opentelemetry-api")

    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework.boot:spring-boot-starter-json")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.awaitility:awaitility")
    testImplementation(libs.json.schema.validator)
    testRuntimeOnly("org.postgresql:postgresql")
}

// Tests check what goes on the wire against the event schemas in contracts/events.
tasks.test {
    systemProperty("solace.contracts.events", rootProject.file("../contracts/events").absolutePath)
    inputs.dir(rootProject.file("../contracts/events"))
}
