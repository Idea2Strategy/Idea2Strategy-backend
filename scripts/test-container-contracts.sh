#!/usr/bin/env bash
set -euo pipefail

builder='eclipse-temurin:21-jdk-jammy@sha256:55fb9bf738f5d9b4a6c01b39337e3070d3e27370dd3c478fd1d5d3cd2233c6d8'
runtime='eclipse-temurin:21-jre-jammy@sha256:3097cbbebb7d490494a98aed2301f284b38f79eba158eef098c6fc8c8af11c23'

for application in backend-api backend-worker backend-batch admin-mcp; do
  dockerfile="apps/${application}/Dockerfile"
  test -f "${dockerfile}"
  grep -F "FROM --platform=\$BUILDPLATFORM ${builder} AS build" "${dockerfile}" >/dev/null
  grep -F "FROM ${runtime}" "${dockerfile}" >/dev/null
  grep -F "./gradlew :apps:${application}:bootJar --no-daemon" "${dockerfile}" >/dev/null
  grep -F "/workspace/apps/${application}/build/libs/${application}-0.1.0-SNAPSHOT.jar" "${dockerfile}" >/dev/null
  grep -F 'USER 10001:10001' "${dockerfile}" >/dev/null
  grep -F 'STOPSIGNAL SIGTERM' "${dockerfile}" >/dev/null
  grep -F 'ENTRYPOINT ["java","-jar","/opt/idea2strategy/application.jar"]' "${dockerfile}" >/dev/null
  if grep -Eq '(^|[/:])latest([@[:space:]]|$)' "${dockerfile}"; then
    echo "mutable latest tag is forbidden: ${dockerfile}" >&2
    exit 1
  fi
done

for application in backend-api admin-mcp; do
  grep -F 'HEALTHCHECK ' "apps/${application}/Dockerfile" >/dev/null
  grep -F '/actuator/health' "apps/${application}/Dockerfile" >/dev/null
done

test -f .dockerignore
for pattern in '.git' '.gradle' '**/build' '.local'; do
  grep -Fx "${pattern}" .dockerignore >/dev/null
done
