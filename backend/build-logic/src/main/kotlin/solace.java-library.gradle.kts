// A shared library in libs/. Libraries ship Spring Boot auto-configurations,
// so a service only has to depend on them.
plugins {
    id("solace.java-conventions")
    id("solace.spring-platform")
    `java-library`
}

dependencies {
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
