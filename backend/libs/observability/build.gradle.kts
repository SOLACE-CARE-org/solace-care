// Traces, metrics and logs over OTLP to the collector (System Design, Observability), with logs
// scrubbed before they leave the process.
plugins {
    id("solace.java-library")
}

dependencies {
    api(project(":libs:log-scrub"))
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("io.micrometer:micrometer-tracing-bridge-otel")
    api("io.opentelemetry:opentelemetry-exporter-otlp")
    api("io.micrometer:micrometer-registry-otlp")
    api(libs.otel.logback.appender)
    implementation("org.springframework.boot:spring-boot-autoconfigure")
}
