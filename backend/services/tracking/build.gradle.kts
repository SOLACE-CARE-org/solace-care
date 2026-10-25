// Tracking: its own service and database (D1). gRPC server for calls from the other services.
plugins {
    id("solace.service")
}

dependencies {
    // Platform settings and flags from Core (D61).
    implementation(project(":libs:core-settings"))
    // Clinicians change word lists, phrases and test cases with a second sign-off (D61).
    implementation(project(":libs:reviewed-lists"))
    implementation("org.springframework.grpc:spring-grpc-server-spring-boot-starter")
    // Calls to Core (live-booking check, taxonomy, age) with deadlines and breakers.
    implementation(project(":libs:grpc-client"))
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    // Module card images (MinIO locally, any S3-compatible store in the cluster).
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3)

    testImplementation("io.grpc:grpc-inprocess")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.testcontainers:minio")
}
