// RFC 9457 problem details with a stable code, a support reference and the trace id.
plugins {
    id("solace.java-library")
}

dependencies {
    api("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.slf4j:slf4j-api")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework.security:spring-security-core")
    compileOnly("io.opentelemetry:opentelemetry-api")

    testImplementation(project(":libs:locale"))
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.security:spring-security-core")
}
