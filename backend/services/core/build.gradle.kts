// Core: a Spring Modulith modular monolith (D1) owning identity, the marketplace, money,
// administration, notifications and the health check.
plugins {
    id("solace.service")
}

dependencies {
    implementation(project(":libs:grpc-client"))
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
}
