// Defaults for every gRPC call between services: 2-second deadline, circuit breaker with a
// required fallback, client-credentials token, client-side round-robin.
plugins {
    id("solace.java-library")
}

dependencies {
    api("org.springframework.grpc:spring-grpc-client-spring-boot-starter")
    api("io.github.resilience4j:resilience4j-circuitbreaker")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.slf4j:slf4j-api")
    compileOnly("org.springframework.security:spring-security-oauth2-client")

    testImplementation(project(":contracts"))
    testImplementation("io.grpc:grpc-inprocess")
    testImplementation("org.springframework.security:spring-security-oauth2-client")
}
