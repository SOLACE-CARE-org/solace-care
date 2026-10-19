// AI: its own service and database (D1). gRPC server for calls from the other services.
plugins {
    id("solace.service")
}

dependencies {
    implementation("org.springframework.grpc:spring-grpc-server-spring-boot-starter")
    // Calls to Core and Tracking for the pre-session brief, with deadlines and breakers.
    implementation(project(":libs:grpc-client"))
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    // Model calls go through the gateway (care.solace.ai.gateway), never straight to a vendor (D56).
    implementation(platform(libs.spring.ai.bom))
    implementation(libs.spring.ai.openai)
    implementation(libs.langgraph4j.core)

    testImplementation("io.grpc:grpc-inprocess")
}
