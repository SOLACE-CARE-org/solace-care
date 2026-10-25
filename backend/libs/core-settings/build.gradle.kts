// Tracking's and AI's platform settings and flags, read from Core (D61): loaded at start, again on
// core.config.changed and every few minutes, with each service's own defaults as the fallback.
plugins {
    id("solace.java-library")
}

dependencies {
    api(project(":libs:grpc-client"))
    implementation(project(":contracts"))
    implementation(project(":libs:event-envelope"))
    implementation(project(":libs:jobs"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.slf4j:slf4j-api")

    testImplementation("io.grpc:grpc-inprocess")
}
