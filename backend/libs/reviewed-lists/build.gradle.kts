// Lists that safety or clinical meaning depends on (guardrail words, safety phrases, evaluation
// sets, the companion's corpus), changed by clinicians with a second clinician's sign-off (D61).
plugins {
    id("solace.java-library")
}

dependencies {
    api("org.springframework:spring-jdbc")
    api("com.fasterxml.jackson.core:jackson-databind")
    api(project(":libs:error-model"))
    implementation(project(":libs:audit"))
    implementation(project(":libs:jobs"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.slf4j:slf4j-api")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("org.springframework.security:spring-security-core")
    compileOnly("org.springframework.security:spring-security-oauth2-jose")
    compileOnly("jakarta.validation:jakarta.validation-api")

    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.springframework.security:spring-security-oauth2-jose")
    testRuntimeOnly("org.postgresql:postgresql")
}
