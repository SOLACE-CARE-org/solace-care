// Spring Cloud Gateway (System Design, Inter-service communication): the one entry point for the
// mobile app and the admin portal. Checks tokens, rate-limits, routes to Core, Tracking and AI.
// Reactive (WebFlux) and stateless: no database.
plugins {
    id("solace.boot-app")
}

dependencies {
    implementation(platform(libs.spring.cloud.bom))
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation(project(":libs:observability"))
    implementation(project(":libs:locale"))
    implementation(project(":libs:error-model"))

    testImplementation(platform(libs.spring.cloud.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.testcontainers:junit-jupiter")
}
