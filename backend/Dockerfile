# One image recipe for every Java service. CI builds the jar with Gradle first, then:
#   docker build --build-arg SERVICE=core -t solace/core backend
# SERVICE is core, tracking, ai or gateway.
ARG JRE_IMAGE=eclipse-temurin:21.0.8_9-jre-noble

FROM ${JRE_IMAGE} AS layers
ARG SERVICE
WORKDIR /extract
COPY services/${SERVICE}/build/libs/${SERVICE}.jar app.jar
# Split the jar into Spring Boot's layers so a code change rebuilds only the last, small layer.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination out

FROM ${JRE_IMAGE}
ARG SERVICE
LABEL org.opencontainers.image.title="solace-${SERVICE}" \
      org.opencontainers.image.source="https://github.com/solace-care/solace"
WORKDIR /app
COPY --from=layers /extract/out/dependencies/ ./
COPY --from=layers /extract/out/spring-boot-loader/ ./
COPY --from=layers /extract/out/snapshot-dependencies/ ./
COPY --from=layers /extract/out/application/ ./
# A numeric, non-root user with no home and no shell, so Kubernetes can enforce runAsNonRoot.
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
