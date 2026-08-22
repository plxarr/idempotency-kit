# Contributing to Idempotency Kit

Thanks for taking the time to contribute! This project is licensed under Apache 2.0 and
welcomes issues, discussions, and pull requests.

## Project layout

Multi-module Maven build (Java 21, Spring Boot 3.4+):

| Module | Artifact | What it contains |
|---|---|---|
| `core` | `idempotency-kit-core` | `@Idempotent` annotation, aspect, `IdempotencyManager`, `IdempotencyStorage` SPI |
| `adapters/redis` | `idempotency-kit-redis` | Redis-backed `IdempotencyStorage` |
| `adapters/caffeine` | `idempotency-kit-caffeine` | Caffeine-backed `IdempotencyStorage` (local/tests) |

A runnable Spring Boot demo lives in its own repository:
[idempotency-kit-demo](https://github.com/plxarr/idempotency-kit-demo).

## Building

```
mvn clean package
```

The Redis adapter's `*IT` tests need a live Redis, so they run in `mvn verify` rather than
`mvn test`. They skip themselves when nothing is listening, so `verify` stays green either
way.

## Before opening a PR

- Keep changes scoped to one concern per PR.
- If you touch `core`, make sure both adapters (`redis`, `caffeine`) still build and behave
  consistently — they implement the same `IdempotencyStorage` contract documented in that
  interface's Javadoc.
- Add or update tests for any behavior change, especially around concurrency (lock
  acquisition, `WAIT`/`REJECT`, TTL expiry). This is a concurrency-sensitive library;
  untested changes to the aspect or the storage adapters are high risk.
- Run `mvn clean package` locally before submitting.

## Reporting bugs / requesting features

Use the GitHub issue templates. For anything touching locking/concurrency semantics,
please include:
- The backend involved (Redis / Caffeine).
- `ttlMs`, `concurrentWaitTimeoutMs`, and `onConcurrent` used.
- Whether the underlying method's real execution time can exceed `ttlMs` — most
  surprising behavior around duplicate results traces back to that.

## Code style

- No unnecessary abstractions or premature configurability — match the existing style in
  `core` (small, focused classes; Javadoc explaining *why*, not *what*).
- Comments and Javadoc in English.
