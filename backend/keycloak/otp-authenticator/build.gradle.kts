// Keycloak provider jar: the members' one-time code sign-in and the staff
// temporary-password expiry check. Loaded from build/libs by infra/docker-compose.yml.
plugins {
    id("solace.java-conventions")
}

dependencies {
    // Provided by the Keycloak server at runtime.
    compileOnly(libs.keycloak.core)
    compileOnly(libs.keycloak.server.spi)
    compileOnly(libs.keycloak.server.spi.private)
    compileOnly(libs.keycloak.services)

    testImplementation(libs.keycloak.core)
    testImplementation(libs.keycloak.server.spi)
    testImplementation(libs.keycloak.server.spi.private)
    testImplementation(libs.keycloak.services)
    testImplementation(libs.testcontainers.keycloak)
    testImplementation(libs.jackson.databind)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.jar {
    archiveFileName = "solace-otp-authenticator.jar"
}

// The integration test imports the real realm files, so they are test resources.
sourceSets.test {
    resources.srcDir(rootProject.file("../infra/keycloak/realms"))
}

tasks.test {
    // The integration test loads the built provider jar into a Keycloak container.
    dependsOn(tasks.jar)
    val providerJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(providerJar)
    // ...and the staff login theme from infra/keycloak/themes.
    val theme = rootProject.file("../infra/keycloak/themes/solace-staff")
    inputs.dir(theme)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dsolace.providerJar=${providerJar.get().asFile.absolutePath}",
            "-Dsolace.staffTheme=${theme.absolutePath}",
        )
    })
}
