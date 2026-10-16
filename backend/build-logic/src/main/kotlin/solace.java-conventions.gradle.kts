// Shared Java settings for every module in services/ and libs/.
plugins {
    java
    id("com.diffplug.spotless")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "care.solace"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(catalog.findVersion("java").get().requiredVersion)
    }
}

dependencies {
    testImplementation(platform(catalog.findLibrary("junit-bom").get()))
    testImplementation(platform(catalog.findLibrary("testcontainers-bom").get()))
    testImplementation(catalog.findLibrary("junit-jupiter").get())
    testImplementation(catalog.findLibrary("assertj-core").get())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = catalog.findVersion("java").get().requiredVersion.toInt()
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat(catalog.findVersion("google-java-format").get().requiredVersion)
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
