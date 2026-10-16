// Aligns Spring Boot, Spring Modulith, Spring gRPC and Resilience4j versions for
// every Spring-based module (libs and services; not the Keycloak extension).
plugins {
    java
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val boms = listOf("spring-boot-bom", "spring-modulith-bom", "spring-grpc-bom", "resilience4j-bom")

dependencies {
    for (bom in boms) {
        val platform = platform(catalog.findLibrary(bom).get())
        implementation(platform)
        testImplementation(platform)
        annotationProcessor(platform)
    }
}
