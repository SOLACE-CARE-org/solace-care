// Core: a Spring Modulith modular monolith (D1) owning identity, the marketplace, money,
// administration, notifications and the health check.
plugins {
    id("solace.service")
}

dependencies {
    implementation(project(":libs:grpc-client"))
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    // Object storage for specialist documents (MinIO locally, any S3-compatible store in the cluster).
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3)

    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("io.grpc:grpc-inprocess")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.testcontainers:minio")
}

// Local runs write SMS and email to the log: no provider is chosen yet (application.yml).
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    environment("SOLACE_NOTIFICATIONS_SENDER", "logging")
    // Placeholder: the fee is not decided (decisions.md: the commission rate is open).
    environment("SOLACE_SPECIALISTS_PRICING_FEE_PERCENT", "20")
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
