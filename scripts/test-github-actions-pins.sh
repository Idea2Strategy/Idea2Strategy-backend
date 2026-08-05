#!/usr/bin/env bash
set -euo pipefail

workflow_root='.github/workflows'
external_action_count=0

while IFS= read -r workflow; do
  while IFS= read -r entry; do
    line_number="${entry%%:*}"
    line="${entry#*:}"
    reference="$(printf '%s\n' "${line}" | sed -E 's/.*uses:[[:space:]]*([^[:space:]#]+).*/\1/')"

    case "${reference}" in
      ./*|docker://*)
        continue
        ;;
    esac

    external_action_count=$((external_action_count + 1))
    case "${reference}" in
      actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1)
        expected_tag='v7.0.1'
        ;;
      actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961)
        expected_tag='v5.7.0'
        ;;
      gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb)
        expected_tag='v6.3.0'
        ;;
      *)
        echo "unapproved or mutable external action at ${workflow}:${line_number}: ${reference}" >&2
        exit 1
        ;;
    esac

    actual_tag="$(printf '%s\n' "${line}" | sed -nE 's/.*#[[:space:]]*([^[:space:]]+)[[:space:]]*$/\1/p')"
    if [[ "${actual_tag}" != "${expected_tag}" ]]; then
      echo "missing exact release tag comment at ${workflow}:${line_number}: expected # ${expected_tag}" >&2
      exit 1
    fi
  done < <(grep -nE '^[[:space:]]*-[[:space:]]+uses:[[:space:]]+' "${workflow}" || true)
done < <(find "${workflow_root}" -type f \( -name '*.yml' -o -name '*.yaml' \) -print | sort)

if [[ "${external_action_count}" -eq 0 ]]; then
  echo 'no external GitHub Actions references were inspected' >&2
  exit 1
fi

echo "verified ${external_action_count} immutable GitHub Actions references"
