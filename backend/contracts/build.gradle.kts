// Java classes generated from the Protobuf contracts in ../contracts/proto (the source of truth).
// Services depend on this module for gRPC stubs; nothing here is written by hand.
import com.google.protobuf.gradle.id

plugins {
    id("solace.java-library")
    alias(libs.plugins.protobuf)
}

val protoVersion = libs.versions.protobuf.get()
val grpcVersion = libs.versions.grpc.get()

dependencies {
    api("com.google.protobuf:protobuf-java")
    api("io.grpc:grpc-protobuf")
    api("io.grpc:grpc-stub")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
}

sourceSets {
    main {
        proto {
            srcDir(rootProject.file("../contracts/proto"))
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protoVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}

// Generated code is not ours to format or lint.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.removeAll(listOf("-Werror"))
    options.compilerArgs.add("-Xlint:none")
}
spotless {
    java {
        targetExclude("build/**")
    }
}
