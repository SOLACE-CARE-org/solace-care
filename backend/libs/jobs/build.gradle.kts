// Scheduled jobs registered from each service's job_trigger table (D61): a paused job is not
// registered when the service starts. Also the admin API that lists and changes the rows.
plugins {
    id("solace.java-library")
}

dependencies {
    api("org.springframework:spring-context")
    api("org.springframework:spring-jdbc")
    api(project(":libs:error-model"))
    implementation(project(":libs:audit"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.slf4j:slf4j-api")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("org.springframework.security:spring-security-core")
    compileOnly("org.springframework.security:spring-security-oauth2-jose")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    compileOnly("jakarta.validation:jakarta.validation-api")

    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.postgresql:postgresql")
}
