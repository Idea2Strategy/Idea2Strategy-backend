#!/usr/bin/env bash
set -euo pipefail

# Git Bash on Windows otherwise rewrites Linux container paths into host paths.
export MSYS_NO_PATHCONV=1

platform="${1:-linux/amd64}"
suffix="${platform##*/}"

for application in backend-api backend-worker backend-batch admin-mcp; do
  image="idea2strategy/${application}:runtime-smoke-${suffix}"
  docker buildx build \
    --platform "${platform}" \
    --load \
    --tag "${image}" \
    --file "apps/${application}/Dockerfile" \
    .

  test "$(docker image inspect "${image}" --format '{{.Architecture}}')" = "${suffix}"
  test "$(docker image inspect "${image}" --format '{{.Config.User}}')" = "10001:10001"
  test "$(docker image inspect "${image}" --format '{{.Config.StopSignal}}')" = "SIGTERM"
  test "$(docker image inspect "${image}" --format '{{json .Config.Entrypoint}}')" \
    = '["java","-jar","/opt/idea2strategy/application.jar"]'

  test "$(docker run --rm --platform "${platform}" --entrypoint id "${image}" -u)" = "10001"
  docker run --rm --platform "${platform}" --entrypoint test "${image}" \
    -r /opt/idea2strategy/application.jar
  docker run --rm --platform "${platform}" --entrypoint java "${image}" -version
  docker run --rm --platform "${platform}" --entrypoint java "${image}" \
    -Djarmode=tools -jar /opt/idea2strategy/application.jar list-layers >/dev/null
done

for application in backend-api admin-mcp; do
  image="idea2strategy/${application}:runtime-smoke-${suffix}"
  docker image inspect "${image}" --format '{{json .Config.Healthcheck.Test}}' \
    | grep -F '/actuator/health' >/dev/null
done
