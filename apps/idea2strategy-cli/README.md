# Idea2Strategy CLI

The CLI provides a JSON-only automation boundary for Basic strategy workflows. It does not expose arbitrary code,
external-data fetching, or direct-order commands.

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
delegation create --name NAME --scopes STRATEGY_EDIT,STRATEGY_VALIDATE
delegation revoke --authorization-id ID
strategy list [--limit 1..100] [--cursor CURSOR]
strategy create --name NAME [--description TEXT]
strategy copy --strategy-id ID --name NAME
strategy edit preview --strategy-id ID --authorization-id ID --credential-id ID --operations-file FILE
strategy edit apply --strategy-id ID --authorization-id ID --credential-id ID --operations-file FILE --preview-hash HASH
strategy validate --strategy-id ID
strategy release --strategy-id ID --validation-run-id ID
```

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
