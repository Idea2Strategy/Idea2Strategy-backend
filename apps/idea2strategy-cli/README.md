# Idea2Strategy CLI

The CLI provides a JSON-only automation boundary for Basic strategy workflows. It does not expose arbitrary code,
external-data fetching, or direct-order commands.

## Install

Download `idea2strategy-<version>.zip` from a `cli-v*` release, verify it against the published
`.sha256`, and unzip it. The launcher is `bin/idea2strategy` (`bin/idea2strategy.bat` on Windows);
put its directory on `PATH`. **Java 21 must already be installed** — the archive carries no runtime.

An external AI tool should run `idea2strategy tool-contract` first and follow the JSON it returns
rather than this file: the contract is what the released binary actually enforces.

## Build from source

Build a local distribution:

```powershell
.\gradlew.bat :apps:idea2strategy-cli:installDist
```

The executable is generated at `apps/idea2strategy-cli/build/install/idea2strategy/bin/idea2strategy.bat`.
Use `--base-url` or `I2S_BASE_URL` to select the API. Credentials are stored under the current user's
`.idea2strategy` directory by default; `I2S_TOKEN` can supply an ephemeral token without writing a file.

Login reads the password from standard input so it is not exposed in the process command line:

```powershell
$password | idea2strategy login --email user@example.com
```

Supported commands:

```text
tool-contract
delegation create --name NAME --scopes STRATEGY_EDIT,STRATEGY_VALIDATE --strategy-id ID[,ID...]
  [--expires-at ISO_8601_INSTANT]
delegation revoke --authorization-id ID
strategy list [--limit 1..100] [--cursor CURSOR]
strategy create --name NAME [--description TEXT]
strategy copy --strategy-id ID --name NAME
strategy edit preview --strategy-id ID --authorization-id ID --credential-id ID --operations-file FILE
strategy edit apply --strategy-id ID --authorization-id ID --credential-id ID --operations-file FILE --preview-hash HASH
  --expected-edit-sequence SEQUENCE
strategy validate --strategy-id ID
strategy release --strategy-id ID --validation-run-id ID --initial-cash-amount AMOUNT --budget-cap-bps BPS
  --broker-rules-version VERSION --accounting-rules-version VERSION --precision-rules-version VERSION
  --fee-policy-id ID --buying-power-buffer-policy-id ID --dataset-manifest-id ID
  --execution-policy-version VERSION --candidate-conflict-policy JSON_OBJECT
operator bootstrap --manifest REVIEWED.json --expected-sha256 LOWERCASE_SHA256
```

A delegated tool can build a strategy from nothing: `ADD_GROUP` creates a trade container, naming
its side, how its blocks combine, how capital is split, and which instruments it trades. A strategy
holds one container per side, so a second container on a side already in use is refused.

A delegation must name the strategies it may edit; one that names none would be granted and then
authorize nothing. `--expires-at` is optional and defaults to 24 hours from the grant. The raw
credential is returned once, in the `create` response, and only its digest is stored — a lost
credential is revoked and replaced, never recovered.

`operator bootstrap` is a one-shot SSM/deployment command, never an HTTP bootstrap route. The reviewed manifest
must name the dedicated PostgreSQL role expected for the deployment and contain only HMAC-protected operator
identity material. Supply database connectivity only through `I2S_BOOTSTRAP_JDBC_URL`,
`I2S_BOOTSTRAP_DB_USER`, and `I2S_BOOTSTRAP_DB_PASSWORD`; errors and output never echo the manifest, database
credentials, identity digest, deployment actor, or grant provenance. The command rejects files over 1 MiB,
duplicate JSON keys, unknown fields, and any mismatch with the separately reviewed SHA-256.

External AI tools must call `tool-contract` first. The returned JSON describes the allowed Basic edit operations,
forbidden capabilities, stable exit codes, and the required two-step edit flow. An AI tool must inspect the preview
`diff`, retain its `previewHash` and `expectedEditSequence`, and send both back with the same operations when applying
the reviewed change. The sequence is what makes the review gate hold across a concurrent owner edit: without it the
server would re-read the document and apply a diff nobody reviewed against its current state.
The CLI rejects arbitrary code, external-data access, direct orders, unapproved delegation scopes, and apply requests
that omit the reviewed preview hash.

Every successful response is written to standard output as `{ "ok": true, "command": "...", "data": ... }`.
Every error is written to standard error as `{ "ok": false, "command": "...", "error": ... }`.

Exit codes are stable:

| Code | Meaning |
|---:|---|
| 0 | Success |
| 2 | Invalid CLI usage |
| 3 | Authentication required or rejected |
| 4 | Authorization or scope rejected |
| 5 | Validation, conflict, or safety rejection |
| 6 | API unavailable or invalid response |
| 70 | Internal CLI or credential-store failure |
