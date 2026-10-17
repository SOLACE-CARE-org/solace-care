// AI: its own service and database (D1). gRPC server for calls from the other services.
plugins {
    id("solace.service")
}

dependencies {
    implementation("org.springframework.grpc:spring-grpc-server-spring-boot-starter")

    testImplementation("io.grpc:grpc-inprocess")
}
