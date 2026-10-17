// A runnable Spring Boot application, shipped as <project>.jar. Used by every service and the gateway.
plugins {
    id("solace.java-conventions")
    id("solace.spring-platform")
    id("org.springframework.boot")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "${project.name}.jar"
}

// Only the boot jar is shipped.
tasks.named<Jar>("jar") {
    enabled = false
}
