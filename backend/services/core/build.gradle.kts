// Core: a Spring Modulith modular monolith (D1) owning identity, the marketplace, money,
// administration, notifications and the health check.
plugins {
    id("solace.service")
}

dependencies {
    implementation(project(":libs:grpc-client"))
    // Serves Tracking: the live-booking check, the specialty taxonomy and a seeker's age (D52).
    implementation("org.springframework.grpc:spring-grpc-server-spring-boot-starter")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    // Object storage for specialist documents (MinIO locally, any S3-compatible store in the cluster).
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3)
    // Slot holds (10 minutes), as the System Design puts them in Redis.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // Email through any mail server, one sender row each (D61).
    implementation("org.springframework.boot:spring-boot-starter-mail")

    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("io.grpc:grpc-inprocess")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.testcontainers:minio")
}

// Local runs write SMS and email to the log through LOGGING sender rows (D61, application.yml).
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    environment("SOLACE_NOTIFICATIONS_SENDER", "logging")
    environment("SOLACE_NOTIFICATIONS_PUSH_SENDER", "logging")
    // No real money locally: the sandbox aggregator (D35).
    environment("SOLACE_PAYMENTS_PROVIDER", "sandbox")
}

dependencies {
    // The sign-up flow test runs real Keycloak with the real realms and the code authenticator.
    testImplementation(libs.testcontainers.keycloak)
}

sourceSets.test {
    resources.srcDir(rootProject.file("../infra/keycloak/realms"))
}

tasks.test {
    val authenticator = project(":keycloak:otp-authenticator")
    dependsOn(authenticator.tasks.named("jar"))
    val providerJar = authenticator.tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(providerJar)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dsolace.providerJar=${providerJar.get().asFile.absolutePath}")
    })
}
