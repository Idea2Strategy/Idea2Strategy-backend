# syntax=docker/dockerfile:1.18@sha256:dabfc0969b935b2080555ace70ee69a5261af8a8f1b4df97b9e7fbcf6722eddf
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-jammy@sha256:55fb9bf738f5d9b4a6c01b39337e3070d3e27370dd3c478fd1d5d3cd2233c6d8 AS build

WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY apps ./apps
COPY modules ./modules
COPY db-migration ./db-migration
RUN --mount=type=cache,id=idea2strategy-backend-gradle,target=/root/.gradle \
    sed -i 's/\r$//' gradlew \
    && chmod +x gradlew \
    && ./gradlew :apps:admin-mcp:bootJar --no-daemon

FROM eclipse-temurin:21-jre-jammy@sha256:3097cbbebb7d490494a98aed2301f284b38f79eba158eef098c6fc8c8af11c23

WORKDIR /opt/idea2strategy
COPY --from=build --chown=10001:10001 /workspace/apps/admin-mcp/build/libs/admin-mcp-0.1.0-SNAPSHOT.jar /opt/idea2strategy/application.jar
ENV SERVER_PORT=8083
USER 10001:10001
EXPOSE 8083
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 CMD wget --no-verbose --tries=1 --spider "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1
ENTRYPOINT ["java","-jar","/opt/idea2strategy/application.jar"]

