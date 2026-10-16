// Token checks for every service: REST resource server for both Keycloak realms (D23), and
// scope-and-audience checks on the gRPC server for service-to-service calls (D2).
plugins {
    id("solace.java-library")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    api(project(":libs:error-model"))
    api(project(":libs:locale"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework.grpc:spring-grpc-server-spring-boot-starter")

    testImplementation(project(":contracts"))
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.grpc:spring-grpc-server-spring-boot-starter")
    testImplementation("org.springframework.grpc:spring-grpc-client-spring-boot-starter")
    testImplementation("io.grpc:grpc-inprocess")
}
